package com.kbook.service.ai;

import com.kbook.common.util.CommonUtils;
import com.kbook.common.util.SseHelper;
import com.kbook.config.CancellableHttpClientBuilder;
import com.kbook.config.ChatModelFactory;
import com.kbook.config.annotation.LogModule;
import com.kbook.entity.AiConversation;
import com.kbook.entity.AiSession;
import com.kbook.repository.AiConversationRepository;
import com.kbook.repository.AiSessionRepository;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.Result;
import dev.langchain4j.service.TokenStream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 管理员 AI 对话服务
 * <p>
 * 为管理员提供独立的 AI 对话能力，使用 BookAdminAssistant 接口，
 * 支持完整的图书管理工具调用（增删改查、扫描、统计等）。
 * 与 AiChatService 不同，使用独立的记忆存储和更大的消息窗口。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@LogModule("图书管理对话")
public class BookAdminChatService {

    /** 会话类型标识：管理员 */
    private static final String TYPE = "admin";

    private final AiConversationRepository conversationRepository;
    private final AiSessionRepository sessionRepository;
    /** 管理员专用工具：增删改查、统计 */
    private final ObjectProvider<AdminBookToolService> adminToolServiceProvider;
    private final BookAdminChatMemory bookAdminChatMemory;

    /** SSE 异步执行线程池 */
    private final ExecutorService sseExecutor = Executors.newCachedThreadPool();

    /** 管理员 Assistant 实例缓存（单例） */
    private final ConcurrentHashMap<String, BookAdminAssistant> adminAssistantCache = new ConcurrentHashMap<>();
    private final ChatModelFactory chatModelFactory;

    /**
     * 创建管理员对话会话
     * @param userId 管理员用户ID
     * @return 带 "admin-" 前缀的会话ID
     */
    public String createSession(Long userId) {
        return "admin-" + UUID.randomUUID();
    }

    /** 获取或构建管理员 Assistant 实例（单例缓存） */
    public BookAdminAssistant getAdminAssistant() {
        return adminAssistantCache.computeIfAbsent("admin", k -> buildAdminAssistant());
    }

    /** 清除管理员 Assistant 缓存，下次调用时重新构建 */
    public void clearCache() {
        adminAssistantCache.clear();
        log.info("管理员 AI Assistant 缓存已清除");
    }

    /**
     * SSE 流式对话：管理员发送消息并通过 SSE 逐 token 推送响应
     * @param userId 管理员用户ID
     * @param sessionId 会话ID
     * @param userMessage 管理员消息内容
     * @return SseEmitter 流式发射器
     */
    public SseEmitter streamChat(Long userId, String sessionId, String userMessage) {
        log.info("========== 管理员 AI 对话请求 ==========");
        log.info("userId={}, sessionId={}, message={}", userId, sessionId, userMessage);

        SseEmitter emitter = new SseEmitter(3_600_000L);

        try {
            emitter.send(SseEmitter.event().name("thinking").data("正在思考..."));
        } catch (Exception ignored) {}

        ensureSession(userId, sessionId, userMessage);
        saveMessage(userId, sessionId, "user", userMessage);

        final long[] executorThreadId = new long[1];
        Future<?> aiFuture = sseExecutor.submit(() -> {
            executorThreadId[0] = Thread.currentThread().getId();
            StringBuilder fullResponse = new StringBuilder();
            StringBuilder fullThinking = new StringBuilder();
            AtomicBoolean cancelled = new AtomicBoolean(false);
            try {
                long startTime = System.currentTimeMillis();
                BookAdminAssistant assistant = getAdminAssistant();

                TokenStream tokenStream = assistant.chatStream(sessionId, userMessage);
                tokenStream
                        .onPartialThinking(pt -> {
                            if (cancelled.get()) return;
                            String thinking = pt.text();
                            if (thinking != null && !thinking.isEmpty()) {
                                fullThinking.append(thinking);
                                if (!SseHelper.safeSendEvent(emitter, "thinking_content", thinking)) {
                                    cancelled.set(true);
                                    Thread.currentThread().interrupt();
                                    log.warn("SSE 连接已关闭，停止 AI 输出: sessionId={}", sessionId);
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
                                    Thread.currentThread().interrupt();
                                    log.warn("SSE 连接已关闭，停止 AI 输出: sessionId={}", sessionId);
                                    throw new RuntimeException("Client disconnected");
                                }
                            }
                        })
                        .onCompleteResponse(response -> {
                            long elapsed = System.currentTimeMillis() - startTime;

                            int apiInputTokens = response.tokenUsage() != null && response.tokenUsage().inputTokenCount() != null
                                    ? response.tokenUsage().inputTokenCount() : 0;
                            int apiOutputTokens = response.tokenUsage() != null && response.tokenUsage().outputTokenCount() != null
                                    ? response.tokenUsage().outputTokenCount() : 0;

                            String responseText = fullResponse.toString().trim();
                            log.info("========== 管理员 AI 流式响应完成 ==========");
                            log.info("耗时: {}ms", elapsed);
                            log.info("API实际token: 输入={}, 输出={}, 总={}", apiInputTokens, apiOutputTokens, apiInputTokens + apiOutputTokens);
                            log.info("Answer: {}", responseText.length() > 500 ? responseText.substring(0, 500) + "..." : responseText);

                            CommonUtils.logAiCall("管理员对话", elapsed, apiInputTokens, apiOutputTokens, responseText);

                            if (cancelled.get()) {
                                log.warn("SSE 连接已断开，跳过发送done事件，仅保存已输出内容: sessionId={}", sessionId);
                            } else {
                                try {
                                    emitter.send(SseEmitter.event().name("done").data("[DONE]"));
                                    emitter.complete();
                                } catch (Exception ignored) {}
                            }

                            String thinkingText = fullThinking.length() > 0 ? fullThinking.toString() : null;
                            saveMessage(userId, sessionId, "assistant", responseText, thinkingText);
                            updateSessionTimestamp(sessionId);
                        })
                        .onError(error -> {
                            if (cancelled.get()) {
                                log.warn("SSE 连接已断开，跳过错误处理: sessionId={}", sessionId);
                                // 仍然保存已输出的部分内容
                                if (!fullResponse.isEmpty()) {
                                    String thinkingText = fullThinking.length() > 0 ? fullThinking.toString() : null;
                                    saveMessage(userId, sessionId, "assistant", fullResponse.toString().trim(), thinkingText);
                                    updateSessionTimestamp(sessionId);
                                }
                                return;
                            }
                            log.error("管理员 AI 流式对话异常: sessionId={}", sessionId, error);
                            clearCache();
                            String errMsg = SseHelper.extractFriendlyError(error);
                            try {
                                emitter.send(SseEmitter.event().name("error").data(errMsg));
                            } catch (Exception ignored) {}
                            try {
                                emitter.send(SseEmitter.event().name("done").data("[DONE]"));
                                emitter.complete();
                            } catch (Exception ignored) {}
                            if (!fullResponse.isEmpty()) {
                                String thinkingText = fullThinking.length() > 0 ? fullThinking.toString() : null;
                                saveMessage(userId, sessionId, "assistant", fullResponse.toString().trim(), thinkingText);
                                updateSessionTimestamp(sessionId);
                            }
                        })
                        .start();

            } catch (Exception e) {
                if (cancelled.get()) return;
                log.error("管理员 AI 对话启动异常: sessionId={}", sessionId, e);
                clearCache();
                String errMsg = SseHelper.extractFriendlyError(e);
                try {
                    emitter.send(SseEmitter.event().name("error").data(errMsg));
                } catch (Exception ignored) {}
                try {
                    emitter.send(SseEmitter.event().name("done").data("[DONE]"));
                    emitter.complete();
                } catch (Exception ignored) {}
            } finally {
                CancellableHttpClientBuilder.clearStream(executorThreadId[0]);
            }
        });

        emitter.onCompletion(() -> {
            CancellableHttpClientBuilder.cancelStream(executorThreadId[0]);
            aiFuture.cancel(true);
        });
        emitter.onTimeout(() -> {
            CancellableHttpClientBuilder.cancelStream(executorThreadId[0]);
            aiFuture.cancel(true);
            log.warn("管理员 SSE 超时: sessionId={}", sessionId);
        });
        emitter.onError((e) -> {
            CancellableHttpClientBuilder.cancelStream(executorThreadId[0]);
            aiFuture.cancel(true);
            log.error("管理员 SSE 错误: sessionId={}", sessionId, e);
        });

        return emitter;
    }

    /**
     * 获取指定会话的历史消息列表
     * @param userId 管理员用户ID
     * @param sessionId 会话ID
     * @return 按时间升序排列的对话记录
     */
    public List<AiConversation> getHistory(Long userId, String sessionId) {
        return conversationRepository.findByUserIdAndSessionIdOrderByCreatedAtAsc(userId, sessionId);
    }

    /**
     * 获取管理员的所有对话会话列表
     * @param userId 管理员用户ID
     * @return 按更新时间降序排列的会话列表
     */
    public List<AiSession> getSessions(Long userId) {
        return sessionRepository.findByUserIdAndTypeOrderByUpdatedAtDesc(userId, TYPE);
    }

    /**
     * 删除指定管理员会话及其所有消息记录
     * @param userId 管理员用户ID
     * @param sessionId 会话ID
     */
    public void deleteSession(Long userId, String sessionId) {
        conversationRepository.deleteByUserIdAndSessionId(userId, sessionId);
        sessionRepository.deleteByUserIdAndSessionId(userId, sessionId);
    }

    /**
     * 非流式对话：管理员发送消息并等待完整响应
     * @param userId 管理员用户ID
     * @param sessionId 会话ID
     * @param userMessage 管理员消息内容
     * @return AI 回复文本
     */
    public String chat(Long userId, String sessionId, String userMessage) {
        log.info("========== 管理员 AI 对话（非流式） ==========");
        ensureSession(userId, sessionId, userMessage);
        saveMessage(userId, sessionId, "user", userMessage);

        BookAdminAssistant assistant = getAdminAssistant();
        try {
            Result<String> result = assistant.chatWithResponse(sessionId, userMessage);
            String responseText = result.content() != null ? result.content().trim() : "";

            log.info("========== 管理员 AI 非流式完整响应 ==========");
            String thinkingContent = null;
            if (result.finalResponse() != null && result.finalResponse().aiMessage() != null) {
                thinkingContent = result.finalResponse().aiMessage().thinking();
                log.info("Thinking长度: {} 字符", thinkingContent != null ? thinkingContent.length() : 0);
            }
            if (result.tokenUsage() != null) {
                log.info("API实际token: 输入={}, 输出={}",
                        result.tokenUsage().inputTokenCount(), result.tokenUsage().outputTokenCount());
            }
            log.info("==============================================");

            saveMessage(userId, sessionId, "assistant", responseText, thinkingContent);
            updateSessionTimestamp(sessionId);
            return responseText;
        } catch (Exception e) {
            log.error("管理员 AI 对话异常: sessionId={}", sessionId, e);
            clearCache();
            throw e;
        }
    }

    /** 构建管理员 Assistant 实例，配置 ChatModel、StreamingChatModel、工具和记忆 */
    private BookAdminAssistant buildAdminAssistant() {
        ChatModel chatModel = chatModelFactory.buildChatModel();
        StreamingChatModel streamingChatModel = chatModelFactory.buildStreamingChatModel();

        log.info("构建管理员 AI Assistant (BookAdminAssistant)...");
        return AiServices.builder(BookAdminAssistant.class)
                .chatModel(chatModel)
                .streamingChatModel(streamingChatModel)
                .chatMemoryProvider(sessionId -> dev.langchain4j.memory.chat.MessageWindowChatMemory.builder()
                        .id(sessionId)
                        .maxMessages(200)
                        .chatMemoryStore(bookAdminChatMemory)
                        .build())
                // 管理员专用工具：增删改查、统计
                .tools(adminToolServiceProvider.getObject())
                .build();
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
                .thinkingContent(thinkingContent)
                .build();
        conversationRepository.save(record);
    }

}
