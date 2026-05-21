package com.kbook.service;

import com.kbook.common.util.CommonUtils;
import com.kbook.common.util.SseHelper;
import com.kbook.constants.AiPromptConstants;
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

@Slf4j
@Service
@RequiredArgsConstructor
public class BookAdminChatService {

    private static final String TYPE = "admin";

    private final AiProviderConfigService providerConfigService;
    private final AiConversationRepository conversationRepository;
    private final AiSessionRepository sessionRepository;
    private final ObjectProvider<AiToolService> toolServiceProvider;
    private final AiChatMemory chatMemoryStore;

    private final ExecutorService sseExecutor = Executors.newCachedThreadPool();

    private final ConcurrentHashMap<String, BookAdminAssistant> adminAssistantCache = new ConcurrentHashMap<>();

    public String createSession(Long userId) {
        return "admin-" + UUID.randomUUID();
    }

    public BookAdminAssistant getAdminAssistant() {
        return adminAssistantCache.computeIfAbsent("admin", k -> buildAdminAssistant());
    }

    public void clearCache() {
        adminAssistantCache.clear();
        log.info("管理员 AI Assistant 缓存已清除");
    }

    public SseEmitter streamChat(Long userId, String sessionId, String userMessage) {
        log.info("========== 管理员 AI 对话请求 ==========");
        log.info("userId={}, sessionId={}, message={}", userId, sessionId, userMessage);

        SseEmitter emitter = new SseEmitter(180_000L);

        try {
            emitter.send(SseEmitter.event().name("thinking").data("正在思考..."));
        } catch (Exception ignored) {}

        ensureSession(userId, sessionId, userMessage);
        saveMessage(userId, sessionId, "user", userMessage);

        sseExecutor.execute(() -> {
            StringBuilder fullResponse = new StringBuilder();
            StringBuilder fullThinking = new StringBuilder();
            try {
                long startTime = System.currentTimeMillis();
                BookAdminAssistant assistant = getAdminAssistant();

                TokenStream tokenStream = assistant.chatStream(sessionId, userMessage);
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

                            String responseText = fullResponse.toString().trim();
                            log.info("========== 管理员 AI 流式响应完成 ==========");
                            log.info("耗时: {}ms", elapsed);
                            log.info("API实际token: 输入={}, 输出={}, 总={}", apiInputTokens, apiOutputTokens, apiInputTokens + apiOutputTokens);
                            log.info("Answer: {}", responseText.length() > 500 ? responseText.substring(0, 500) + "..." : responseText);

                            CommonUtils.logAiCall("管理员对话", elapsed, apiInputTokens, apiOutputTokens, responseText);

                            try {
                                emitter.send(SseEmitter.event().name("done").data("[DONE]"));
                                emitter.complete();
                            } catch (Exception ignored) {}

                            String thinkingText = fullThinking.length() > 0 ? fullThinking.toString() : null;
                            saveMessage(userId, sessionId, "assistant", responseText, thinkingText);
                            updateSessionTimestamp(sessionId);
                        })
                        .onError(error -> {
                            log.error("管理员 AI 流式对话异常: sessionId={}", sessionId, error);
                            providerConfigService.clearAssistantCache();
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
                log.error("管理员 AI 对话启动异常: sessionId={}", sessionId, e);
                providerConfigService.clearAssistantCache();
                clearCache();
                String errMsg = SseHelper.extractFriendlyError(e);
                try {
                    emitter.send(SseEmitter.event().name("error").data(errMsg));
                } catch (Exception ignored) {}
                try {
                    emitter.send(SseEmitter.event().name("done").data("[DONE]"));
                    emitter.complete();
                } catch (Exception ignored) {}
            }
        });

        emitter.onTimeout(() -> log.warn("管理员 SSE 超时: sessionId={}", sessionId));
        emitter.onError((e) -> log.error("管理员 SSE 错误: sessionId={}", sessionId, e));

        return emitter;
    }

    public List<AiConversation> getHistory(Long userId, String sessionId) {
        return conversationRepository.findByUserIdAndSessionIdOrderByCreatedAtAsc(userId, sessionId);
    }

    public List<AiSession> getSessions(Long userId) {
        return sessionRepository.findByUserIdAndTypeOrderByUpdatedAtDesc(userId, TYPE);
    }

    public void deleteSession(Long userId, String sessionId) {
        conversationRepository.deleteByUserIdAndSessionId(userId, sessionId);
        sessionRepository.deleteByUserIdAndSessionId(userId, sessionId);
    }

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

    private BookAdminAssistant buildAdminAssistant() {
        ChatModel chatModel = providerConfigService.buildChatChatModel();
        StreamingChatModel streamingChatModel = providerConfigService.buildChatStreamingModel();
        AiToolService realToolService = toolServiceProvider.getObject();

        log.info("构建管理员 AI Assistant (BookAdminAssistant)...");
        return AiServices.builder(BookAdminAssistant.class)
                .chatModel(chatModel)
                .streamingChatModel(streamingChatModel)
                .chatMemoryProvider(sessionId -> dev.langchain4j.memory.chat.MessageWindowChatMemory.builder()
                        .id(sessionId)
                        .maxMessages(AiPromptConstants.ADMIN_MAX_MESSAGES)
                        .chatMemoryStore(chatMemoryStore)
                        .build())
                .tools(realToolService)
                .build();
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

}
