package com.kbook.config;

import com.kbook.common.util.SseHelper;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.*;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 流式 StreamingChatModel 熔断器装饰器
 * <p>
 * 装饰链：CircuitBreakerStreamingChatModel → 实际 StreamingChatModel
 * <p>
 * 流式调用的错误是异步的（通过 handler.onError 回调），不能用 executeSupplier。
 * 改为手动管理熔断器生命周期：
 * <ol>
 *   <li>调用前 acquirePermission（熔断开启时抛 CallNotPermittedException）</li>
 *   <li>onCompleteResponse → onSuccess</li>
 *   <li>onError 且为可重试异常 → onError（触发熔断计数）</li>
 *   <li>onError 但为客户端错误 → onSuccess（不归咎于 provider）</li>
 * </ol>
 */
@Slf4j
public class CircuitBreakerStreamingChatModel implements StreamingChatModel {

    private final StreamingChatModel delegate;
    private final CircuitBreaker circuitBreaker;

    public CircuitBreakerStreamingChatModel(StreamingChatModel delegate, CircuitBreaker circuitBreaker) {
        this.delegate = delegate;
        this.circuitBreaker = circuitBreaker;
    }

    @Override
    public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
        long start = System.nanoTime();
        circuitBreaker.acquirePermission();

        StreamingChatResponseHandler wrappedHandler = wrapHandler(handler, start);
        delegate.doChat(request, wrappedHandler);
    }

    /**
     * 覆写 chat() — 委托给 delegate.chat() 而非走默认接口方法。
     * <p>
     * 关键：OpenAiStreamingChatModel 覆写了 chat() 来构建 OpenAiChatRequestParameters
     * （含 reasoningEffort / returnThinking 等字段）。若走 StreamingChatModel 接口的
     * 默认 chat() 方法，会构建 DefaultChatRequestParameters，传给 delegate.doChat()
     * 时触发 ClassCastException。
     * <p>
     * 因此必须委托给 delegate.chat()，让 delegate 用自己的参数构建逻辑。
     */
    @Override
    public void chat(List<ChatMessage> messages, StreamingChatResponseHandler handler) {
        long start = System.nanoTime();
        circuitBreaker.acquirePermission();

        StreamingChatResponseHandler wrappedHandler = wrapHandler(handler, start);
        delegate.chat(messages, wrappedHandler);
    }

    /**
     * 包装 handler — 在 onCompleteResponse/onError 中通知熔断器
     */
    private StreamingChatResponseHandler wrapHandler(StreamingChatResponseHandler handler, long start) {
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
                long duration = System.nanoTime() - start;
                circuitBreaker.onSuccess(duration, TimeUnit.NANOSECONDS);
                handler.onCompleteResponse(completeResponse);
            }

            @Override
            public void onError(Throwable error) {
                long duration = System.nanoTime() - start;
                // 仅可重试异常（429/5xx/网络）计入熔断器失败
                // 客户端错误（400/401/编程异常）不归咎于 provider
                if (SseHelper.isRetryableError(error)) {
                    circuitBreaker.onError(duration, TimeUnit.NANOSECONDS, error);
                } else {
                    circuitBreaker.onSuccess(duration, TimeUnit.NANOSECONDS);
                }
                handler.onError(error);
            }
        };
    }

    /**
     * 获取底层熔断器状态（用于监控/日志）
     */
    public CircuitBreaker.State getState() {
        return circuitBreaker.getState();
    }
}
