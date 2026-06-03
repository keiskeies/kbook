package com.kbook.service;

import com.kbook.common.util.CommonUtils;
import com.kbook.common.util.SseHelper;
import com.kbook.entity.AiConversation;
import com.kbook.entity.AiSession;
import com.kbook.repository.AiConversationRepository;
import com.kbook.repository.AiSessionRepository;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.service.Result;
import dev.langchain4j.service.TokenStream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AI 通用对话服务
 * <p>
 * 负责用户与 AI 助理的通用对话功能，包括会话管理、消息持久化、
 * 非流式对话和 SSE 流式对话。对话上下文通过 AiChatMemory 管理，
 * 消息记录持久化到 AiConversation 表。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiChatService {

    /** 会话类型标识：通用助理 */
    private static final String TYPE = "assistant";

    private final AiConversationRepository conversationRepository;
    private final AiSessionRepository sessionRepository;
    private final AiProviderConfigService providerConfigService;
    private final AiChatMemory chatMemoryStore;

    /** SSE 异步执行线程池 */
    private final ExecutorService sseExecutor = Executors.newCachedThreadPool();

    /**
     * 创建新的对话会话，并初始化 SystemMessage 到 ChatMemory
     * @param userId 用户ID
     * @return 新创建的会话ID
     */
    public String createSession(Long userId) {
        String sessionId = UUID.randomUUID().toString();
        try {
            var smAnnotation = AiAssistant.class.getAnnotation(dev.langchain4j.service.SystemMessage.class);
            if (smAnnotation != null) {
                String systemText = String.join("\n", smAnnotation.value());
                systemText = systemText.replace("{{userId}}", String.valueOf(userId));
                chatMemoryStore.updateMessages(sessionId,
                        List.of(SystemMessage.from(systemText)));
                log.debug("已为会话 {} 初始化 SystemMessage ({} 字符)", sessionId, systemText.length());
            }
        } catch (Exception e) {
            log.warn("初始化 SystemMessage 失败: {}", e.getMessage());
        }
        return sessionId;
    }

    /**
     * SSE 流式对话：发送消息并通过 SSE 逐 token 推送响应
     * @param userId 用户ID
     * @param sessionId 会话ID
     * @param userMessage 用户消息内容
     * @return SseEmitter 流式发射器
     */
    public SseEmitter streamChat(Long userId, String sessionId, String userMessage) {
        log.info("========== AI 流式对话请求 ==========");
        log.info("用户ID: {}", userId);
        log.info("会话ID: {}", sessionId);
        log.info("问题内容: {}", userMessage);

        SseEmitter emitter = new SseEmitter(3_600_000L);

        try {
            emitter.send(SseEmitter.event().name("thinking").data("正在思考..."));
        } catch (Exception ignored) {
        }

        ensureSession(userId, sessionId, userMessage);
        saveMessage(userId, sessionId, "user", userMessage);

        RequestAttributes requestAttributes = RequestContextHolder.getRequestAttributes();
        SecurityContext securityContext = SecurityContextHolder.getContext();

        sseExecutor.execute(() -> {
            SecurityContextHolder.setContext(securityContext);
            if (requestAttributes != null) {
                RequestContextHolder.setRequestAttributes(requestAttributes);
            }
            ToolResultContext ctx = new ToolResultContext();
            ToolResultContext.bind(ctx);
            if (requestAttributes != null) {
                RequestContextHolder.setRequestAttributes(requestAttributes);
            }
            StringBuilder fullResponse = new StringBuilder();
            StringBuilder fullThinking = new StringBuilder();
            AtomicBoolean cancelled = new AtomicBoolean(false);
            try {
                long startTime = System.currentTimeMillis();
                AiAssistant assistant = providerConfigService.getChatAssistant();
                if (assistant == null) {
                    log.warn("AI 助理未配置: userId={}", userId);
                    String hint = "AI 助理暂未配置，请联系管理员在后台配置 LLM 接口后再试。";
                    emitter.send(SseEmitter.event().name("message").data(hint));
                    emitter.send(SseEmitter.event().name("done").data("[DONE]"));
                    emitter.complete();
                    saveMessage(userId, sessionId, "assistant", hint);
                    updateSessionTimestamp(sessionId);
                    return;
                }

                TokenStream tokenStream = assistant.chatStream(sessionId, userId, userMessage);
                tokenStream
                        .onPartialThinking(pt -> {
                            if (cancelled.get()) return;
                            String thinking = pt.text();
                            if (thinking != null && !thinking.isEmpty()) {
                                fullThinking.append(thinking);
                                if (!SseHelper.safeSendEvent(emitter, "thinking_content", thinking)) {
                                    cancelled.set(true);
                                    throw new RuntimeException("Client disconnected");
                                }
                            }
                        })
                        .onPartialResponse(token -> {
                            if (cancelled.get()) return;
                            fullResponse.append(token);
                            if (!token.isEmpty()) {
                                if (!SseHelper.safeSendEvent(emitter, "message", token)) {
                                    cancelled.set(true);
                                    throw new RuntimeException("Client disconnected");
                                }
                            }
                        })
                        .onCompleteResponse(response -> {
                            if (cancelled.get()) return;
                            long elapsed = System.currentTimeMillis() - startTime;

                            int apiInputTokens = response.tokenUsage() != null && response.tokenUsage().inputTokenCount() != null
                                    ? response.tokenUsage().inputTokenCount() : 0;
                            int apiOutputTokens = response.tokenUsage() != null && response.tokenUsage().outputTokenCount() != null
                                    ? response.tokenUsage().outputTokenCount() : 0;

                            String text = fullResponse.toString().trim();
                            log.info("========== AI 流式对话响应完成 ==========");
                            log.info("耗时: {}ms", elapsed);
                            log.info("API实际token: 输入={}, 输出={}, 总={}", apiInputTokens, apiOutputTokens, apiInputTokens + apiOutputTokens);
                            log.info("Answer: {}", text.length() > 500 ? text.substring(0, 500) + "..." : text);

                            CommonUtils.logAiCall("流式对话", elapsed, apiInputTokens, apiOutputTokens, text);

                            try {
                                if (ctx.hasBooks()) {
                                    String bookMapJson = new com.fasterxml.jackson.databind.ObjectMapper()
                                            .writeValueAsString(ctx.getBookMap());
                                    emitter.send(SseEmitter.event().name("book_map").data(bookMapJson));
                                    log.debug("下发 book_map: {} 本书", ctx.getBookMap().size());
                                }
                            } catch (Exception ignored) {
                            }

                            try {
                                emitter.send(SseEmitter.event().name("done").data("[DONE]"));
                                emitter.complete();
                            } catch (Exception ignored) {
                            }

                            saveMessage(userId, sessionId, "assistant", text,
                                    fullThinking.length() > 0 ? fullThinking.toString() : null);
                            updateSessionTimestamp(sessionId);
                        })
                        .onError(error -> {
                            if (Thread.currentThread().isInterrupted()) return;
                            if (isConnectionReset(error) && !fullResponse.isEmpty()) {
                                log.warn("Connection reset 但已有部分响应，视为成功: sessionId={}, 已接收={}字符",
                                        sessionId, fullResponse.length());
                                String text = fullResponse.toString().trim();
                                long elapsed = System.currentTimeMillis() - startTime;
                                CommonUtils.logAiCall("流式对话(连接重置)", elapsed, 0, 0, text);
                                try {
                                    if (ctx.hasBooks()) {
                                        String bookMapJson = new com.fasterxml.jackson.databind.ObjectMapper()
                                                .writeValueAsString(ctx.getBookMap());
                                        emitter.send(SseEmitter.event().name("book_map").data(bookMapJson));
                                    }
                                    emitter.send(SseEmitter.event().name("done").data("[DONE]"));
                                    emitter.complete();
                                } catch (Exception ignored) {
                                }
                                saveMessage(userId, sessionId, "assistant", text);
                                updateSessionTimestamp(sessionId);
                                return;
                            }
                            log.error("流式对话异常: sessionId={}", sessionId, error);
                            providerConfigService.clearAssistantCache();
                            String errMsg = SseHelper.extractFriendlyError(error);
                            try {
                                emitter.send(SseEmitter.event().name("error").data(errMsg));
                            } catch (Exception ignored) {
                            }
                            try {
                                emitter.send(SseEmitter.event().name("done").data("[DONE]"));
                                emitter.complete();
                            } catch (Exception ignored) {
                            }
                            if (!fullResponse.isEmpty()) {
                                saveMessage(userId, sessionId, "assistant", fullResponse.toString());
                                updateSessionTimestamp(sessionId);
                            }
                        })
                        .start();

            } catch (Exception e) {
                if (cancelled.get()) return;
                log.error("流式对话启动异常: sessionId={}", sessionId, e);
                providerConfigService.clearAssistantCache();
                String errMsg = SseHelper.extractFriendlyError(e);
                try {
                    emitter.send(SseEmitter.event().name("error").data(errMsg));
                } catch (Exception ignored) {
                }
                try {
                    emitter.send(SseEmitter.event().name("done").data("[DONE]"));
                    emitter.complete();
                } catch (Exception ignored) {
                }
            } finally {
                ToolResultContext.unbind();
                SecurityContextHolder.clearContext();
                RequestContextHolder.resetRequestAttributes();
            }
        });

        emitter.onTimeout(() -> log.warn("SSE 超时: sessionId={}", sessionId));
        emitter.onError((e) -> log.error("SSE 错误: sessionId={}", sessionId, e));

        return emitter;
    }

    /**
     * 获取指定会话的历史消息列表
     * @param userId 用户ID
     * @param sessionId 会话ID
     * @return 按时间升序排列的对话记录
     */
    public List<AiConversation> getHistory(Long userId, String sessionId) {
        return conversationRepository.findByUserIdAndSessionIdOrderByCreatedAtAsc(userId, sessionId);
    }

    /**
     * 获取用户的所有通用对话会话列表
     * @param userId 用户ID
     * @return 按更新时间降序排列的会话列表
     */
    public List<AiSession> getSessions(Long userId) {
        return sessionRepository.findByUserIdAndTypeOrderByUpdatedAtDesc(userId, TYPE);
    }

    /**
     * 删除指定会话及其所有消息记录
     * @param userId 用户ID
     * @param sessionId 会话ID
     */
    @Transactional
    public void deleteSession(Long userId, String sessionId) {
        conversationRepository.deleteByUserIdAndSessionId(userId, sessionId);
        sessionRepository.deleteByUserIdAndSessionId(userId, sessionId);
    }

    /** 确保会话记录存在，不存在则自动创建 */
    private void ensureSession(Long userId, String sessionId, String userMessage) {
        sessionRepository.findBySessionId(sessionId).orElseGet(() -> {
            String title = userMessage.length() > 30 ? userMessage.substring(0, 30) + "..." : userMessage;
            AiSession session = AiSession.builder()
                    .userId(userId)
                    .type(TYPE)
                    .sessionId(sessionId)
                    .title(title)
                    .build();
            return sessionRepository.save(session);
        });
    }

    /** 更新会话的最后活跃时间 */
    private void updateSessionTimestamp(String sessionId) {
        sessionRepository.findBySessionId(sessionId).ifPresent(session -> {
            session.setUpdatedAt(java.time.LocalDateTime.now());
            sessionRepository.save(session);
        });
    }

    /** 保存消息记录（无思考内容） */
    private void saveMessage(Long userId, String sessionId, String role, String content) {
        saveMessage(userId, sessionId, role, content, null);
    }

    /** 保存消息记录（含思考内容） */
    private void saveMessage(Long userId, String sessionId, String role, String content, String thinkingContent) {
        AiConversation record = AiConversation.builder()
                .userId(userId)
                .sessionId(sessionId)
                .type(TYPE)
                .role(role)
                .content(content)
                .compressedContent(content) // 初始时压缩内容等于原始内容
                .thinkingContent(thinkingContent)
                .build();
        conversationRepository.save(record);
    }

    /** 判断异常是否为 Connection reset，用于自动重试决策 */
    private boolean isConnectionReset(Throwable error) {
        if (error == null) return false;
        String msg = error.getMessage();
        if (msg != null && msg.contains("Connection reset")) return true;
        if (error instanceof ResourceAccessException) {
            Throwable cause = error.getCause();
            if (cause != null) {
                String causeMsg = cause.getMessage();
                return causeMsg != null && causeMsg.contains("Connection reset");
            }
        }
        return false;
    }

}
