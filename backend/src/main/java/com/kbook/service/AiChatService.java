package com.kbook.service;

import com.kbook.common.util.CommonUtils;
import com.kbook.common.util.SseHelper;
import com.kbook.entity.AiConversation;
import com.kbook.repository.AiConversationRepository;
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

/**
 * AI 对话服务 — 管理会话、流式输出、历史记录
 * <p>
 * 使用 AiProviderConfigService 获取全局 AiAssistant，
 * AI 模型配置统一由 application.yml 管理。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiChatService {

    private final AiConversationRepository conversationRepository;
    private final AiProviderConfigService providerConfigService;
    private final AiChatMemory chatMemoryStore;

    private final ExecutorService sseExecutor = Executors.newCachedThreadPool();

    /**
     * 创建新会话 — 同时初始化 ChatMemory 中的 SystemMessage
     * <p>
     * LangChain4j 1.13.0 在 streaming 模式下不会自动把 @SystemMessage 写入 ChatMemory，
     * 导致后续请求中 SystemMessage 丢失。这里手动补偿。
     */
    public String createSession(Long userId) {
        String sessionId = UUID.randomUUID().toString();
        // 手动将 @SystemMessage 写入 ChatMemory
        try {
            var smAnnotation = AiAssistant.class.getAnnotation(dev.langchain4j.service.SystemMessage.class);
            if (smAnnotation != null) {
                String systemText = String.join("\n", smAnnotation.value());
                // 替换 {{userId}} 模板变量
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
     * 非流式对话
     */
    @Transactional
    public String chat(Long userId, String sessionId, String userMessage) {
        log.info("========== AI 对话请求 ==========");
        log.info("用户ID: {}", userId);
        log.info("会话ID: {}", sessionId);
        log.info("问题内容: {}", userMessage);

        saveMessage(userId, sessionId, "user", userMessage);
        AiAssistant assistant = providerConfigService.getChatAssistant(userId);
        if (assistant == null) {
            log.warn("AI 助理未配置: userId={}", userId);
            return "AI 助理暂未配置，请联系管理员在后台配置 LLM 接口后再试。";
        }
        try {
            long startTime = System.currentTimeMillis();
            Result<String> result = assistant.chatWithResponse(sessionId, userId, userMessage);
            long elapsed = System.currentTimeMillis() - startTime;
            String text = result.content();

            // 解析实际 API token 用量
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

            // 记录 AI 调用日志（使用 API 实际 token 用量）
            CommonUtils.logAiCall("对话", elapsed, apiInputTokens, apiOutputTokens, text);
            return text;
        } catch (Exception e) {
            // Connection reset 时自动重试一次（空闲连接被服务端关闭）
            if (isConnectionReset(e)) {
                log.warn("检测到 Connection reset，自动重试一次: userId={}, sessionId={}", userId, sessionId);
                try {
                    long startTime = System.currentTimeMillis();
                    Result<String> result = assistant.chatWithResponse(sessionId, userId, userMessage);
                    long elapsed = System.currentTimeMillis() - startTime;
                    String text = result.content();
                    log.info("重试成功: userId={}, sessionId={}, 耗时={}ms", userId, sessionId, elapsed);
                    saveMessage(userId, sessionId, "assistant", text);
                    CommonUtils.logAiCall("对话(重试)", elapsed, 0, 0, text);
                    return text;
                } catch (Exception retryEx) {
                    log.error("重试仍然失败: userId={}, sessionId={}, error={}", userId, sessionId, retryEx.getMessage());
                    providerConfigService.clearAssistantCache();
                    throw retryEx;
                }
            }
            // 模型调用失败，清除缓存以便下次重新构建
            log.error("AI 对话异常: userId={}, sessionId={}, error={}", userId, sessionId, e.getMessage());
            log.info("====================================\n");
            providerConfigService.clearAssistantCache();
            throw e;
        }
    }

    /**
     * 流式对话 — SSE（真正的 Token 级流式，使用 StreamingChatModel）
     */
    public SseEmitter streamChat(Long userId, String sessionId, String userMessage) {
        log.info("========== AI 流式对话请求 ==========");
        log.info("用户ID: {}", userId);
        log.info("会话ID: {}", sessionId);
        log.info("问题内容: {}", userMessage);

        SseEmitter emitter = new SseEmitter(120_000L);

        // 立即发送 thinking 事件，让前端知道请求已被接受
        try {
            emitter.send(SseEmitter.event().name("thinking").data("正在思考..."));
        } catch (Exception ignored) {
        }

        saveMessage(userId, sessionId, "user", userMessage);

        // 捕获当前请求上下文和安全上下文，传给工作线程
        RequestAttributes requestAttributes = RequestContextHolder.getRequestAttributes();
        SecurityContext securityContext = SecurityContextHolder.getContext();

        sseExecutor.execute(() -> {
            // 恢复安全上下文（@EnableMethodSecurity 需要 SecurityContext 才能正常工作）
            SecurityContextHolder.setContext(securityContext);
            // 恢复请求上下文到工作线程（供其他 @RequestScope bean 使用）
            if (requestAttributes != null) {
                RequestContextHolder.setRequestAttributes(requestAttributes);
            }
            // 创建并绑定本请求的 ToolResultContext（ThreadLocal，不依赖 Spring 代理）
            ToolResultContext ctx = new ToolResultContext();
            ToolResultContext.bind(ctx);
            // 恢复请求上下文到工作线程（供其他 @RequestScope bean 使用）
            if (requestAttributes != null) {
                RequestContextHolder.setRequestAttributes(requestAttributes);
            }
            StringBuilder fullResponse = new StringBuilder();
            try {
                long startTime = System.currentTimeMillis();
                AiAssistant assistant = providerConfigService.getChatAssistant(userId);
                if (assistant == null) {
                    log.warn("AI 助理未配置: userId={}", userId);
                    String hint = "AI 助理暂未配置，请联系管理员在后台配置 LLM 接口后再试。";
                    emitter.send(SseEmitter.event().name("message").data(hint));
                    emitter.send(SseEmitter.event().name("done").data("[DONE]"));
                    emitter.complete();
                    saveMessage(userId, sessionId, "assistant", hint);
                    return;
                }

                TokenStream tokenStream = assistant.chatStream(sessionId, userId, userMessage);
                StringBuilder fullThinking = new StringBuilder();
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

                            // 解析 token 用量
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

                            // 下发书名→bookId 映射，让前端把《书名》渲染为可点击卡片
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

                            saveMessage(userId, sessionId, "assistant", text);
                        })
                        .onError(error -> {
                            // Connection reset 且已有部分响应时，视为成功（流式传输中连接被关闭是常见的）
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
                            // 保存已接收的部分响应
                            if (!fullResponse.isEmpty()) {
                                saveMessage(userId, sessionId, "assistant", fullResponse.toString());
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
                // 清理 ThreadLocal 和请求上下文，避免线程复用时残留
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
     * 获取对话历史
     */
    public List<AiConversation> getHistory(Long userId, String sessionId) {
        return conversationRepository.findByUserIdAndSessionIdOrderByCreatedAtAsc(userId, sessionId);
    }

    /**
     * 获取用户的会话 ID 列表（去重，最近优先）
     */
    public List<String> getSessionIds(Long userId) {
        return conversationRepository.findSessionIdsByUserId(userId);
    }

    /**
     * 删除会话
     */
    @Transactional
    public void deleteSession(Long userId, String sessionId) {
        conversationRepository.deleteByUserIdAndSessionId(userId, sessionId);
    }

    private void saveMessage(Long userId, String sessionId, String role, String content) {
        AiConversation record = AiConversation.builder()
                .userId(userId)
                .sessionId(sessionId)
                .role(role)
                .content(content)
                .build();
        conversationRepository.save(record);
    }

    /**
     * 检测是否为 Connection reset 异常
     * 空闲连接被服务端/代理关闭时，HTTP 客户端复用 stale 连接会抛出此异常
     */
    private boolean isConnectionReset(Throwable error) {
        if (error == null) return false;
        String msg = error.getMessage();
        if (msg != null && msg.contains("Connection reset")) return true;
        if (error instanceof ResourceAccessException) {
            Throwable cause = error.getCause();
            if (cause != null) {
                String causeMsg = cause.getMessage();
                if (causeMsg != null && causeMsg.contains("Connection reset")) return true;
            }
        }
        return false;
    }

    /**
     * 流式请求出错时的降级处理
     */
    private void fallbackErrorHandling(SseEmitter emitter, Throwable error,
                                        StringBuilder fullResponse, Long userId, String sessionId) {
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
        }
    }

}
