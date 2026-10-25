package com.kbook.service.ai.core.model;

import com.kbook.service.ai.core.AiUsageContext;
import com.kbook.service.ai.core.AiUsageLogService;
import com.kbook.service.ai.core.AiUsageLogService.UsageEvent;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.PartialResponse;
import dev.langchain4j.model.chat.response.PartialResponseContext;
import dev.langchain4j.model.chat.response.PartialThinking;
import dev.langchain4j.model.chat.response.PartialThinkingContext;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.output.TokenUsage;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import com.kbook.service.ai.core.model.UsageRecordingStreamingChatModel;

/**
 * 流式 StreamingChatModel 用量记录装饰器。
 * <p>
 * 在发起调用时捕获用户/图书/会话快照（流式回调线程不再持有 MDC），
 * 每次 onCompleteResponse 记录 token、费用、延迟、结束原因；
 * onError 记录失败，用于成功率统计。
 */
public class UsageRecordingStreamingChatModel implements StreamingChatModel {

    private final StreamingChatModel delegate;
    private final AiUsageLogService usageLogService;
    private final String scene;
    private final String modelName;
    private final String configName;
    private final Double inputPrice;
    private final Double outputPrice;

    public UsageRecordingStreamingChatModel(StreamingChatModel delegate, AiUsageLogService usageLogService,
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
    public void chat(List<ChatMessage> messages, StreamingChatResponseHandler handler) {
        delegate.chat(messages, wrap(handler));
    }

    @Override
    public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
        // 与 CircuitBreakerStreamingChatModel 一致：委托 chat 让 delegate 构建正确的请求参数类型
        delegate.chat(request, wrap(handler));
    }

    private StreamingChatResponseHandler wrap(StreamingChatResponseHandler handler) {
        AiUsageContext.Target target = AiUsageContext.current();
        long start = System.nanoTime();
        AtomicBoolean recorded = new AtomicBoolean(false);

        return new StreamingChatResponseHandler() {
            @Override
            public void onPartialThinking(PartialThinking partialThinking, PartialThinkingContext context) {
                handler.onPartialThinking(partialThinking, context);
            }

            @Override
            public void onPartialResponse(PartialResponse partialResponse, PartialResponseContext context) {
                handler.onPartialResponse(partialResponse, context);
            }

            @Override
            public void onCompleteResponse(ChatResponse completeResponse) {
                if (recorded.compareAndSet(false, true)) {
                    record(completeResponse, target, elapsedMs(start), true, null);
                }
                handler.onCompleteResponse(completeResponse);
            }

            @Override
            public void onError(Throwable error) {
                if (recorded.compareAndSet(false, true)) {
                    record(null, target, elapsedMs(start), false,
                            error != null ? error.getClass().getSimpleName() : "UNKNOWN");
                }
                handler.onError(error);
            }
        };
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
                    "STREAM",
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
