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

@Slf4j
@Service
@RequiredArgsConstructor
public class AiChatService {

    private static final String TYPE = "assistant";

    private final AiConversationRepository conversationRepository;
    private final AiSessionRepository sessionRepository;
    private final AiProviderConfigService providerConfigService;
    private final AiChatMemory chatMemoryStore;

    private final ExecutorService sseExecutor = Executors.newCachedThreadPool();

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

    @Transactional
    public String chat(Long userId, String sessionId, String userMessage) {
        log.info("========== AI 对话请求 ==========");
        log.info("用户ID: {}", userId);
        log.info("会话ID: {}", sessionId);
        log.info("问题内容: {}", userMessage);

        ensureSession(userId, sessionId, userMessage);
        saveMessage(userId, sessionId, "user", userMessage);

        AiAssistant assistant = providerConfigService.getChatAssistant();
        if (assistant == null) {
            log.warn("AI 助理未配置: userId={}", userId);
            return "AI 助理暂未配置，请联系管理员在后台配置 LLM 接口后再试。";
        }
        try {
            long startTime = System.currentTimeMillis();
            Result<String> result = assistant.chatWithResponse(sessionId, userId, userMessage);
            long elapsed = System.currentTimeMillis() - startTime;
            String text = result.content();

            String thinkingContent = null;
            int thinkingLength = 0;
            int apiInputTokens = 0;
            int apiOutputTokens = 0;
            if (result.finalResponse() != null && result.finalResponse().aiMessage() != null) {
                thinkingContent = result.finalResponse().aiMessage().thinking();
                thinkingLength = thinkingContent != null ? thinkingContent.length() : 0;
            }
            if (result.tokenUsage() != null) {
                apiInputTokens = result.tokenUsage().inputTokenCount() != null ? result.tokenUsage().inputTokenCount() : 0;
                apiOutputTokens = result.tokenUsage().outputTokenCount() != null ? result.tokenUsage().outputTokenCount() : 0;
            }

            log.info("========== AI 对话完整响应 ==========");
            log.info("耗时: {}ms", elapsed);
            log.info("Thinking长度: {} 字符 | Thinking前200字: {}",
                    thinkingLength,
                    thinkingContent != null && thinkingContent.length() > 200
                            ? thinkingContent.substring(0, 200) + "..."
                            : thinkingContent);
            log.info("API实际token: 输入={}, 输出={}, 总={}", apiInputTokens, apiOutputTokens, apiInputTokens + apiOutputTokens);
            log.info("Answer: {}", text != null && text.length() > 500 ? text.substring(0, 500) + "..." : text);
            log.info("FinishReason: {}", result.finishReason());
            log.info("======================================");

            saveMessage(userId, sessionId, "assistant", text);
            updateSessionTimestamp(sessionId);

            CommonUtils.logAiCall("对话", elapsed, apiInputTokens, apiOutputTokens, text);
            return text;
        } catch (Exception e) {
            if (isConnectionReset(e)) {
                log.warn("检测到 Connection reset，自动重试一次: userId={}, sessionId={}", userId, sessionId);
                try {
                    long startTime = System.currentTimeMillis();
                    Result<String> result = assistant.chatWithResponse(sessionId, userId, userMessage);
                    long elapsed = System.currentTimeMillis() - startTime;
                    String text = result.content();
                    log.info("重试成功: userId={}, sessionId={}, 耗时={}ms", userId, sessionId, elapsed);
                    saveMessage(userId, sessionId, "assistant", text);
                    updateSessionTimestamp(sessionId);
                    CommonUtils.logAiCall("对话(重试)", elapsed, 0, 0, text);
                    return text;
                } catch (Exception retryEx) {
                    log.error("重试仍然失败: userId={}, sessionId={}, error={}", userId, sessionId, retryEx.getMessage());
                    providerConfigService.clearAssistantCache();
                    throw retryEx;
                }
            }
            log.error("AI 对话异常: userId={}, sessionId={}, error={}", userId, sessionId, e.getMessage());
            log.info("====================================\n");
            providerConfigService.clearAssistantCache();
            throw e;
        }
    }

    public SseEmitter streamChat(Long userId, String sessionId, String userMessage) {
        log.info("========== AI 流式对话请求 ==========");
        log.info("用户ID: {}", userId);
        log.info("会话ID: {}", sessionId);
        log.info("问题内容: {}", userMessage);

        SseEmitter emitter = new SseEmitter(120_000L);

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
                            String thinking = pt.text();
                            if (thinking != null && !thinking.isEmpty()) {
                                fullThinking.append(thinking);
                                try {
                                    emitter.send(SseEmitter.event().name("thinking_content").data(thinking));
                                } catch (Exception e) {
                                    log.warn("SSE发送thinking失败: {}", e.getMessage());
                                }
                            }
                        })
                        .onPartialResponse(token -> {
                            fullResponse.append(token);
                            if (!token.isEmpty()) {
                                try {
                                    emitter.send(SseEmitter.event().name("message").data(token));
                                } catch (Exception e) {
                                    log.warn("SSE发送token失败: {}", e.getMessage());
                                }
                            }
                        })
                        .onCompleteResponse(response -> {
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

    public List<AiConversation> getHistory(Long userId, String sessionId) {
        return conversationRepository.findByUserIdAndSessionIdOrderByCreatedAtAsc(userId, sessionId);
    }

    public List<AiSession> getSessions(Long userId) {
        return sessionRepository.findByUserIdAndTypeOrderByUpdatedAtDesc(userId, TYPE);
    }

    @Transactional
    public void deleteSession(Long userId, String sessionId) {
        conversationRepository.deleteByUserIdAndSessionId(userId, sessionId);
        sessionRepository.deleteByUserIdAndSessionId(userId, sessionId);
    }

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

    private void updateSessionTimestamp(String sessionId) {
        sessionRepository.findBySessionId(sessionId).ifPresent(session -> {
            session.setUpdatedAt(java.time.LocalDateTime.now());
            sessionRepository.save(session);
        });
    }

    private void saveMessage(Long userId, String sessionId, String role, String content) {
        saveMessage(userId, sessionId, role, content, null);
    }

    private void saveMessage(Long userId, String sessionId, String role, String content, String thinkingContent) {
        AiConversation record = AiConversation.builder()
                .userId(userId)
                .sessionId(sessionId)
                .type(TYPE)
                .role(role)
                .content(content)
                .thinkingContent(thinkingContent)
                .build();
        conversationRepository.save(record);
    }

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
