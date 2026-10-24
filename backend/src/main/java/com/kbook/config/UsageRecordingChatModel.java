package com.kbook.config;

import com.kbook.service.ai.core.AiUsageContext;
import com.kbook.service.ai.core.AiUsageLogService;
import com.kbook.service.ai.core.AiUsageLogService.UsageEvent;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;

import java.util.List;

/**
 * 非流式 ChatModel 用量记录装饰器。
 * <p>
 * 位于熔断/重试包装器之外，按场景记录每次真实 API 调用的 token、费用、延迟与成功状态。
 */
public class UsageRecordingChatModel implements ChatModel {

    private final ChatModel delegate;
    private final AiUsageLogService usageLogService;
    private final String scene;
    private final String modelName;
    private final String configName;
    private final Double inputPrice;
    private final Double outputPrice;

    public UsageRecordingChatModel(ChatModel delegate, AiUsageLogService usageLogService,
                                   String scene, String modelName, String configName,
                                   Double inputPrice, Double outputPrice) {
        this.delegate = delegate;
        this.usageLogService = usageLogService;
        this.scene = scene;
        this.modelName = modelName;
        this.configName = configName;
        this.inputPrice = inputPrice;
        this.outputPrice = outputPrice;
    }

    @Override
    public ChatResponse chat(List<ChatMessage> messages) {
        long start = System.nanoTime();
        AiUsageContext.Target target = AiUsageContext.current();
        try {
            ChatResponse response = delegate.chat(messages);
            record(response, target, elapsedMs(start), true, null);
            return response;
        } catch (RuntimeException e) {
            record(null, target, elapsedMs(start), false, e.getClass().getSimpleName());
            throw e;
        }
    }

    @Override
    public ChatResponse doChat(ChatRequest request) {
        long start = System.nanoTime();
        AiUsageContext.Target target = AiUsageContext.current();
        try {
            ChatResponse response = delegate.chat(request);
            record(response, target, elapsedMs(start), true, null);
            return response;
        } catch (RuntimeException e) {
            record(null, target, elapsedMs(start), false, e.getClass().getSimpleName());
            throw e;
        }
    }

    private void record(ChatResponse response, AiUsageContext.Target target,
                        long latencyMs, boolean success, String errorType) {
        try {
            TokenUsage usage = response != null ? response.tokenUsage() : null;
            String finishReason = response != null && response.finishReason() != null
                    ? response.finishReason().name() : null;
            usageLogService.record(new UsageEvent(scene, modelName, configName,
                    usage != null && usage.inputTokenCount() != null ? usage.inputTokenCount() : 0,
                    usage != null && usage.outputTokenCount() != null ? usage.outputTokenCount() : 0,
                    "CHAT",
                    target != null ? target.userId() : null,
                    target != null ? target.bookId() : null,
                    target != null ? target.sessionId() : null,
                    inputPrice, outputPrice, latencyMs, success, finishReason, errorType));
        } catch (Exception ignored) {
            // 用量统计失败不影响业务调用
        }
    }

    private long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }
}
