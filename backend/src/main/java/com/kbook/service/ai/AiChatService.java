package com.kbook.service.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kbook.common.util.CommonUtils;
import com.kbook.common.util.SseHelper;
import com.kbook.entity.AiConversation;
import com.kbook.service.ai.streaming.ThoughtTagParser;
import com.kbook.entity.AiSession;
import com.kbook.repository.AiConversationRepository;
import com.kbook.repository.AiSessionRepository;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.service.Result;
import dev.langchain4j.service.TokenStream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
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
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.kbook.common.util.QueryBuilder.*;

/**
 * AI 通用对话服务
 * <p>
 * 负责用户与 AI 助理的通用对话功能，包括会话管理、消息持久化、
 * 非流式对话和 SSE 流式对话。对话上下文通过 AiChatMemory 管理，
 * 消息记录持久化到 AiConversation 表。
 */
@Slf4j
@Service
public class AiChatService {

    /** 会话类型标识：通用助理 */
    private static final String TYPE = "assistant";

    private final AiConversationRepository conversationRepository;
    private final AiSessionRepository sessionRepository;
    private final AiProviderConfigService providerConfigService;
    private final AiChatMemory chatMemoryStore;
    private final ExecutorService sseExecutor;
    private final ObjectMapper objectMapper;

    public AiChatService(
            AiConversationRepository conversationRepository,
            AiSessionRepository sessionRepository,
            AiProviderConfigService providerConfigService,
            AiChatMemory chatMemoryStore,
            @Qualifier("sseExecutor") ExecutorService sseExecutor,
            ObjectMapper objectMapper) {
        this.conversationRepository = conversationRepository;
        this.sessionRepository = sessionRepository;
        this.providerConfigService = providerConfigService;
        this.chatMemoryStore = chatMemoryStore;
        this.sseExecutor = sseExecutor;
        this.objectMapper = objectMapper;
    }

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

        // 创建 SSE 发送器，设置 1 小时超时
        SseEmitter emitter = new SseEmitter(3_600_000L);

        // 确保会话记录存在
        ensureSession(userId, sessionId, userMessage);

        // 发送 sessionId 回前端，确保多轮对话上下文不丢失
        try {
            emitter.send(SseEmitter.event().name("session_id").data(sessionId));
            emitter.send(SseEmitter.event().name("thinking").data("正在思考..."));
        } catch (Exception ignored) {
        }

        // 压缩历史消息以控制内存使用，然后清空当前会话的聊天记忆
        chatMemoryStore.compressHistoryIfNeeded(userId, sessionId);
        chatMemoryStore.deleteMessages(sessionId);

        // 保存当前线程的请求上下文和安全上下文，以便在异步线程中使用
        RequestAttributes requestAttributes = RequestContextHolder.getRequestAttributes();
        SecurityContext securityContext = SecurityContextHolder.getContext();

        // 在独立线程中执行 AI 对话，避免阻塞主线程
        Future<?> aiFuture = sseExecutor.submit(() -> {
            // 恢复安全上下文
            SecurityContextHolder.setContext(securityContext);
            // 恢复请求上下文
            if (requestAttributes != null) {
                RequestContextHolder.setRequestAttributes(requestAttributes);
            }
            // 创建工具结果上下文，用于收集 AI 工具调用的结果
            ToolResultContext ctx = new ToolResultContext();
            ToolResultContext.bind(ctx);
            if (requestAttributes != null) {
                RequestContextHolder.setRequestAttributes(requestAttributes);
            }
            StringBuilder fullResponse = new StringBuilder();
            StringBuilder fullThinking = new StringBuilder();
            AtomicBoolean cancelled = new AtomicBoolean(false);
            // Google AI 的 <thought> 标签解析器
            ThoughtTagParser thoughtParser = new ThoughtTagParser();
            try {
                long startTime = System.currentTimeMillis();
                AiAssistant assistant = providerConfigService.getChatAssistant();
                if (assistant == null) {
                    log.warn("AI 助理未配置: userId={}", userId);
                    String hint = "AI 助理暂未配置，请联系管理员在后台配置 LLM 接口后再试。";
                    emitter.send(SseEmitter.event().name("message").data(hint));
                    emitter.send(SseEmitter.event().name("done").data("[DONE]"));
                    emitter.complete();
                    saveMessage(userId, sessionId, "user", userMessage);
                    saveMessage(userId, sessionId, "assistant", hint);
                    updateSessionTimestamp(sessionId);
                    return;
                }

                // 启动流式对话
                TokenStream tokenStream = assistant.chatStream(sessionId, userId, userMessage);
                tokenStream
                        // 处理思考过程（如果有）
                        .onPartialThinking(pt -> {
                            if (cancelled.get()) return;
                            String thinking = pt.text();
                            if (thinking != null && !thinking.isEmpty()) {
                                fullThinking.append(thinking);
                                // 发送思考内容到前端
                                if (!SseHelper.safeSendEvent(emitter, "thinking_content", thinking)) {
                                    cancelled.set(true);
                                    Thread.currentThread().interrupt();
                                    log.warn("SSE 连接已关闭，停止 AI 输出: sessionId={}", sessionId);
                                    throw new RuntimeException("Client disconnected");
                                }
                            }
                        })
                        // 处理部分响应（逐 token）— 解析 <thought> 标签分离思考内容
                        .onPartialResponse(token -> {
                            if (cancelled.get()) return;

                            ThoughtTagParser.Result parsed = thoughtParser.process(token);

                            // 处理分离出的思考内容
                            if (parsed.hasThinking()) {
                                fullThinking.append(parsed.thinking());
                                if (!SseHelper.safeSendEvent(emitter, "thinking_content", parsed.thinking())) {
                                    cancelled.set(true);
                                    Thread.currentThread().interrupt();
                                    log.warn("SSE 连接已关闭，停止 AI 输出: sessionId={}", sessionId);
                                    throw new RuntimeException("Client disconnected");
                                }
                            }

                            // 处理正常回复内容
                            if (parsed.hasMessage()) {
                                fullResponse.append(parsed.message());
                                if (!SseHelper.safeSendEvent(emitter, "message", parsed.message())) {
                                    cancelled.set(true);
                                    Thread.currentThread().interrupt();
                                    log.warn("SSE 连接已关闭，停止 AI 输出: sessionId={}", sessionId);
                                    throw new RuntimeException("Client disconnected");
                                }
                            }
                        })
                        // 响应完成
                        .onCompleteResponse(response -> {
                            long elapsed = System.currentTimeMillis() - startTime;

                            // 统计 token 使用量
                            int apiInputTokens = response.tokenUsage() != null && response.tokenUsage().inputTokenCount() != null
                                    ? response.tokenUsage().inputTokenCount() : 0;
                            int apiOutputTokens = response.tokenUsage() != null && response.tokenUsage().outputTokenCount() != null
                                    ? response.tokenUsage().outputTokenCount() : 0;

                            String text = fullResponse.toString().trim();

                            // 记录 AI 调用摘要日志（一次 LLM 调用只打一条 INFO）
                            CommonUtils.logAiSummarySimple("流式对话", elapsed, apiInputTokens, apiOutputTokens,
                                    String.format("sessionId=%s", sessionId), CommonUtils.truncateText(text, 80));

                            // 输出审查 P1 #17：检测系统提示泄露
                            String safeText = CommonUtils.sanitizeAiOutput(text);

                            if (cancelled.get()) {
                                log.warn("SSE 连接已断开，跳过发送done事件，仅保存已输出内容: sessionId={}", sessionId);
                            } else {
                                // 检测到泄露时发送 replace 事件覆盖前端已显示的流式内容
                                if (!safeText.equals(text)) {
                                    try {
                                        emitter.send(SseEmitter.event().name("replace").data(safeText));
                                        log.warn("已发送 replace 事件覆盖泄露内容: sessionId={}", sessionId);
                                    } catch (Exception ignored) {
                                    }
                                }

                                // 如果有书籍工具调用结果，发送 book_map 事件
                                try {
                                    if (ctx.hasBooks()) {
                                        String bookMapJson = objectMapper.writeValueAsString(ctx.getBookMap());
                                        emitter.send(SseEmitter.event().name("book_map").data(bookMapJson));
                                        log.debug("下发 book_map: {} 本书", ctx.getBookMap().size());
                                    }
                                } catch (Exception ignored) {
                                }

                                // 发送完成事件
                                try {
                                    emitter.send(SseEmitter.event().name("done").data("[DONE]"));
                                    emitter.complete();
                                } catch (Exception ignored) {
                                }
                            }

                            // 持久化对话消息（输出审查 P1 #17：使用已审查的 safeText）
                            saveMessage(userId, sessionId, "user", userMessage);
                            saveMessage(userId, sessionId, "assistant", safeText,
                                    fullThinking.length() > 0 ? fullThinking.toString() : null);
                            updateSessionTimestamp(sessionId);
                        })
                        // 错误处理
                        .onError(error -> {
                            if (cancelled.get()) {
                                log.warn("SSE 连接已断开，跳过错误处理: sessionId={}", sessionId);
                                // 仍然保存已输出的部分内容
                                if (!fullResponse.isEmpty()) {
                                    saveMessage(userId, sessionId, "user", userMessage);
                                    saveMessage(userId, sessionId, "assistant",
                                            CommonUtils.sanitizeAiOutput(fullResponse.toString().trim()));
                                    updateSessionTimestamp(sessionId);
                                }
                                return;
                            }
                            if (Thread.currentThread().isInterrupted()) return;
                            // 连接重置但已有部分响应时，视为成功
                            if (isConnectionReset(error) && !fullResponse.isEmpty()) {
                                log.warn("Connection reset 但已有部分响应，视为成功: sessionId={}, 已接收={}字符",
                                        sessionId, fullResponse.length());
                                String text = fullResponse.toString().trim();
                                long elapsed = System.currentTimeMillis() - startTime;
                                CommonUtils.logAiSummarySimple("流式对话(连接重置)", elapsed, 0, 0,
                                        String.format("sessionId=%s", sessionId), CommonUtils.truncateText(text, 80));
                                try {
                                    if (ctx.hasBooks()) {
                                        String bookMapJson = objectMapper.writeValueAsString(ctx.getBookMap());
                                        emitter.send(SseEmitter.event().name("book_map").data(bookMapJson));
                                    }
                                    emitter.send(SseEmitter.event().name("done").data("[DONE]"));
                                    emitter.complete();
                                } catch (Exception ignored) {
                                }
                                saveMessage(userId, sessionId, "user", userMessage);
                                saveMessage(userId, sessionId, "assistant", CommonUtils.sanitizeAiOutput(text));
                                updateSessionTimestamp(sessionId);
                                return;
                            }
                            // 其他错误
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
                            // 如果有部分响应，仍然保存
                            if (!fullResponse.isEmpty()) {
                                saveMessage(userId, sessionId, "user", userMessage);
                                saveMessage(userId, sessionId, "assistant",
                                        CommonUtils.sanitizeAiOutput(fullResponse.toString()));
                                updateSessionTimestamp(sessionId);
                            }
                        })
                        .start();

            } catch (Exception e) {
                // 启动异常处理
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
                // 清理线程上下文
                ToolResultContext.unbind();
                SecurityContextHolder.clearContext();
                RequestContextHolder.resetRequestAttributes();
            }
        });

        emitter.onCompletion(() -> aiFuture.cancel(true));
        emitter.onTimeout(() -> {
            aiFuture.cancel(true);
            log.warn("SSE 超时: sessionId={}", sessionId);
        });
        emitter.onError((e) -> {
            aiFuture.cancel(true);
            log.error("SSE 错误: sessionId={}", sessionId, e);
        });

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
        return sessionRepository.query()
                .where(AiSession::getUserId, eq(userId))
                .and(AiSession::getType, eq(TYPE))
                .orderByDesc(AiSession::getUpdatedAt)
                .list();
    }

    /**
     * 删除指定会话及其所有消息记录
     * @param userId 用户ID
     * @param sessionId 会话ID
     */
    @Transactional
    public void deleteSession(Long userId, String sessionId) {
        conversationRepository.deleteByUserIdAndSessionId(userId, sessionId);
        sessionRepository.query()
                .where(AiSession::getUserId, eq(userId))
                .and(AiSession::getSessionId, eq(sessionId))
                .list().forEach(sessionRepository::delete);
    }

    /** 确保会话记录存在，不存在则自动创建 */
    private void ensureSession(Long userId, String sessionId, String userMessage) {
        sessionRepository.query()
                .where(AiSession::getSessionId, eq(sessionId))
                .list(1)
                .stream().findFirst()
                .orElseGet(() -> {
                    String title = CommonUtils.truncateText(userMessage, 30);
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
        sessionRepository.query()
                .where(AiSession::getSessionId, eq(sessionId))
                .list(1)
                .stream().findFirst()
                .ifPresent(session -> {
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
