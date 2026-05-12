package com.kbook.service;

import com.kbook.common.util.CommonUtils;
import com.kbook.entity.AiConversation;
import com.kbook.repository.AiConversationRepository;
import dev.langchain4j.service.Result;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * AI 对话服务 — 管理会话、流式输出、历史记录
 * <p>
 * 使用 AiProviderConfigService 获取全局 AiAssistant，
 * 管理员通过配置界面动态切换模型提供商。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiChatService {

    private final AiConversationRepository conversationRepository;
    private final AiProviderConfigService providerConfigService;

    private final ExecutorService sseExecutor = Executors.newCachedThreadPool();

    /**
     * 创建新会话
     */
    public String createSession(Long userId) {
        return UUID.randomUUID().toString();
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
        AiAssistant assistant = providerConfigService.getAssistant(userId);
        if (assistant == null) {
            log.warn("AI 助理未配置: userId={}", userId);
            return "AI 助理暂未配置，请联系管理员在后台配置 LLM 接口后再试。";
        }
        try {
            long startTime = System.currentTimeMillis();
            String thinkingSuffix = providerConfigService.getThinkingPromptSuffix();
            log.info("发送消息到 AI: sessionId={}, thinkingSuffix='{}', 消息长度={}",
                    sessionId, thinkingSuffix, userMessage.length());
            Result<String> result = assistant.chatWithResponse(sessionId, userMessage + thinkingSuffix);
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
            // 模型调用失败，清除缓存以便下次重新构建
            log.error("AI 对话异常: userId={}, sessionId={}, error={}", userId, sessionId, e.getMessage());
            log.info("====================================\n");
            providerConfigService.clearAssistantCache();
            throw e;
        }
    }

    /**
     * 流式对话 — SSE（使用非流式 Assistant + 分段发送模拟打字效果）
     * <p>
     * 真正的 Token 级流式需要 StreamingChatModel，当前方案为兼容性实现。
     */
    public SseEmitter streamChat(Long userId, String sessionId, String userMessage) {
        log.info("========== AI 流式对话请求 ==========");
        log.info("用户ID: {}", userId);
        log.info("会话ID: {}", sessionId);
        log.info("问题内容: {}", userMessage);
        
        SseEmitter emitter = new SseEmitter(120_000L);

        saveMessage(userId, sessionId, "user", userMessage);

        sseExecutor.execute(() -> {
            StringBuilder fullResponse = new StringBuilder();
            try {
                long startTime = System.currentTimeMillis();
                AiAssistant assistant = providerConfigService.getAssistant(userId);
                if (assistant == null) {
                    log.warn("AI 助理未配置: userId={}", userId);
                    String hint = "AI 助理暂未配置，请联系管理员在后台配置 LLM 接口后再试。";
                    SseEmitter.SseEventBuilder event = SseEmitter.event()
                            .name("message")
                            .data(hint);
                    emitter.send(event);
                    
                    SseEmitter.SseEventBuilder doneEvent = SseEmitter.event()
                            .name("done")
                            .data("[DONE]");
                    emitter.send(doneEvent);
                    emitter.complete();
                    saveMessage(userId, sessionId, "assistant", hint);
                    return;
                }
                String thinkingSuffix = providerConfigService.getThinkingPromptSuffix();
                log.info("发送流式消息到 AI: sessionId={}, thinkingSuffix='{}', 消息长度={}",
                        sessionId, thinkingSuffix, userMessage.length());
                Result<String> result = assistant.chatWithResponse(sessionId, userMessage + thinkingSuffix);
                long elapsed = System.currentTimeMillis() - startTime;
                String responseText = result.content();
                fullResponse.append(responseText);
                
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
                
                log.info("========== AI 流式对话完整响应 ==========");
                log.info("耗时: {}ms", elapsed);
                log.info("Thinking长度: {} 字符 | Thinking前200字: {}",
                        thinkingLength,
                        thinkingContent != null && thinkingContent.length() > 200
                                ? thinkingContent.substring(0, 200) + "..."
                                : thinkingContent);
                log.info("API实际token: 输入={}, 输出={}, 总={}", apiInputTokens, apiOutputTokens, apiInputTokens + apiOutputTokens);
                log.info("Answer: {}", responseText != null && responseText.length() > 500 ? responseText.substring(0, 500) + "..." : responseText);
                log.info("FinishReason: {}", result.finishReason());
                log.info("==========================================");

                // 记录 AI 调用日志（使用 API 实际 token 用量）
                CommonUtils.logAiCall("流式对话", elapsed, apiInputTokens, apiOutputTokens, responseText);

                // 分段发送，模拟打字效果
                int chunkSize = 3;
                for (int i = 0; i < responseText.length(); i += chunkSize) {
                    int end = Math.min(i + chunkSize, responseText.length());
                    String chunk = responseText.substring(i, end);
                    SseEmitter.SseEventBuilder event = SseEmitter.event()
                            .name("message")
                            .data(chunk);
                    emitter.send(event);
                    Thread.sleep(30);
                }

                SseEmitter.SseEventBuilder doneEvent = SseEmitter.event()
                        .name("done")
                        .data("[DONE]");
                emitter.send(doneEvent);
                emitter.complete();

                saveMessage(userId, sessionId, "assistant", fullResponse.toString());

            } catch (Exception e) {
                log.error("流式对话异常: sessionId={}", sessionId, e);
                log.info("====================================\n");
                // 模型调用失败，清除缓存以便下次重新构建
                providerConfigService.clearAssistantCache();
                String errMsg = extractFriendlyError(e);
                try {
                    SseEmitter.SseEventBuilder errorEvent = SseEmitter.event()
                            .name("error")
                            .data(errMsg);
                    emitter.send(errorEvent);
                } catch (Exception ignored) {}
                emitter.completeWithError(e);
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

    /** 从异常信息中提取用户友好的错误提示 */
    private String extractFriendlyError(Exception e) {
        String msg = e.getMessage();
        if (msg == null) {
            return "AI 响应异常，请稍后重试。";
        }
        if (msg.contains("not found")) {
            return "AI 模型不存在，请管理员检查模型配置。";
        }
        if (msg.contains("timed out") || msg.contains("Timeout")) {
            return "AI 响应超时，请检查模型服务是否正常运行。";
        }
        if (msg.contains("Connection refused") || msg.contains("connect")) {
            return "无法连接到 AI 模型服务，请检查端点地址和网络。";
        }
        if (msg.contains("401") || msg.contains("Unauthorized") || msg.contains("api key")) {
            return "AI 服务认证失败，请检查 API Key 配置。";
        }
        if (msg.contains("429") || msg.contains("rate limit")) {
            return "AI 服务请求过于频繁，请稍后重试。";
        }
        return "AI 响应异常，请稍后重试。";
    }
}
