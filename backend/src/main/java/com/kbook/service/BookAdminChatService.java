package com.kbook.service;

import com.kbook.common.util.CommonUtils;
import com.kbook.entity.AiConversation;
import com.kbook.repository.AiConversationRepository;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
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

/**
 * 管理员 AI 对话服务 — 图书管理员专属聊天
 * <p>
 * 使用 BookAdminAssistant 接口（不同于普通用户的 AiAssistant），
 * 拥有完整的图书管理工具能力和管理员视角的系统提示词。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BookAdminChatService {

    private final AiProviderConfigService providerConfigService;
    private final AiConversationRepository conversationRepository;
    private final ObjectProvider<AiToolService> toolServiceProvider;
    private final AiChatMemory chatMemoryStore;

    private final ExecutorService sseExecutor = Executors.newCachedThreadPool();

    /** BookAdminAssistant 缓存（key: "admin", 全局共用） */
    private final ConcurrentHashMap<String, BookAdminAssistant> adminAssistantCache = new ConcurrentHashMap<>();

    /**
     * 创建新会话
     */
    public String createSession(Long userId) {
        return "admin-" + UUID.randomUUID().toString();
    }

    /**
     * 获取管理员 AI 助理（带缓存）
     */
    public BookAdminAssistant getAdminAssistant() {
        return adminAssistantCache.computeIfAbsent("admin", k -> buildAdminAssistant());
    }

    /**
     * 清除缓存
     */
    public void clearCache() {
        adminAssistantCache.clear();
    }

    /**
     * 流式对话 — SSE
     */
    public SseEmitter streamChat(Long userId, String sessionId, String userMessage) {
        log.info("========== 管理员 AI 对话请求 ==========");
        log.info("userId={}, sessionId={}, message={}", userId, sessionId, userMessage);

        SseEmitter emitter = new SseEmitter(180_000L); // 3分钟超时（管理操作可能较慢）

        saveMessage(userId, sessionId, "user", userMessage);

        sseExecutor.execute(() -> {
            StringBuilder fullResponse = new StringBuilder();
            try {
                long startTime = System.currentTimeMillis();
                BookAdminAssistant assistant = getAdminAssistant();
                String thinkingSuffix = providerConfigService.getThinkingPromptSuffix();
                String response = assistant.chat(sessionId, userMessage + thinkingSuffix);
                long elapsed = System.currentTimeMillis() - startTime;
                fullResponse.append(response);

                // 记录日志
                int inputTokens = CommonUtils.estimateTokens(userMessage);
                int outputTokens = CommonUtils.estimateTokens(response);
                CommonUtils.logAiCall("管理员对话", elapsed, inputTokens, outputTokens, response);

                // 分段发送
                int chunkSize = 3;
                for (int i = 0; i < response.length(); i += chunkSize) {
                    int end = Math.min(i + chunkSize, response.length());
                    String chunk = response.substring(i, end);
                    emitter.send(SseEmitter.event().name("message").data(chunk));
                    Thread.sleep(30);
                }

                emitter.send(SseEmitter.event().name("done").data("[DONE]"));
                emitter.complete();
                saveMessage(userId, sessionId, "assistant", fullResponse.toString());

            } catch (Exception e) {
                log.error("管理员 AI 对话异常: sessionId={}", sessionId, e);
                providerConfigService.clearAssistantCache();
                clearCache();
                String errMsg = extractFriendlyError(e);
                try {
                    emitter.send(SseEmitter.event().name("error").data(errMsg));
                } catch (Exception ignored) {}
                emitter.completeWithError(e);
            }
        });

        emitter.onTimeout(() -> log.warn("管理员 SSE 超时: sessionId={}", sessionId));
        emitter.onError((e) -> log.error("管理员 SSE 错误: sessionId={}", sessionId, e));

        return emitter;
    }

    /**
     * 获取对话历史
     */
    public List<AiConversation> getHistory(Long userId, String sessionId) {
        return conversationRepository.findByUserIdAndSessionIdOrderByCreatedAtAsc(userId, sessionId);
    }

    /**
     * 获取会话列表
     */
    public List<String> getSessions(Long userId) {
        List<String> allSessions = conversationRepository.findSessionIdsByUserId(userId);
        return allSessions.stream()
                .filter(sid -> sid.startsWith("admin-"))
                .toList();
    }

    /**
     * 删除会话
     */
    public void deleteSession(Long userId, String sessionId) {
        conversationRepository.deleteByUserIdAndSessionId(userId, sessionId);
    }

    /**
     * 非流式对话
     */
    public String chat(Long userId, String sessionId, String userMessage) {
        log.info("========== 管理员 AI 对话（非流式） ==========");
        saveMessage(userId, sessionId, "user", userMessage);

        BookAdminAssistant assistant = getAdminAssistant();
        String thinkingSuffix = providerConfigService.getThinkingPromptSuffix();
        try {
            String response = assistant.chat(sessionId, userMessage + thinkingSuffix);
            saveMessage(userId, sessionId, "assistant", response);
            return response;
        } catch (Exception e) {
            log.error("管理员 AI 对话异常: sessionId={}", sessionId, e);
            clearCache();
            throw e;
        }
    }

    // ==================== 内部方法 ====================

    private BookAdminAssistant buildAdminAssistant() {
        ChatModel chatModel = providerConfigService.buildTagChatModel();
        AiToolService realToolService = toolServiceProvider.getObject();

        log.info("构建管理员 AI Assistant (BookAdminAssistant)...");
        return AiServices.builder(BookAdminAssistant.class)
                .chatModel(chatModel)
                .chatMemoryProvider(sessionId -> dev.langchain4j.memory.chat.MessageWindowChatMemory.builder()
                        .id(sessionId)
                        .maxMessages(20)
                        .chatMemoryStore(chatMemoryStore)
                        .build())
                .tools(realToolService)
                .build();
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

    private String extractFriendlyError(Exception e) {
        String msg = e.getMessage();
        if (msg == null) return "AI 响应异常，请稍后重试。";
        if (msg.contains("timed out") || msg.contains("Timeout")) return "AI 响应超时，请稍后重试。";
        if (msg.contains("Connection refused")) return "无法连接 AI 服务，请检查模型状态。";
        if (msg.contains("401") || msg.contains("api key")) return "AI 服务认证失败，请检查 API Key。";
        if (msg.contains("429")) return "AI 服务请求过于频繁，请稍后重试。";
        return "AI 响应异常，请稍后重试。";
    }
}
