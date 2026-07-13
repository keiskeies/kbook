package com.kbook.config;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * 非流式 ChatModel 熔断器装饰器
 * <p>
 * 装饰链：CircuitBreakerChatModel → RetryableChatModel → 实际模型
 * <p>
 * 熔断器开启时直接抛 {@link io.github.resilience4j.circuitbreaker.CallNotPermittedException}，
 * 避免无效请求排队耗尽线程池。
 */
@Slf4j
public class CircuitBreakerChatModel implements ChatModel {

    private final ChatModel delegate;
    private final CircuitBreaker circuitBreaker;

    public CircuitBreakerChatModel(ChatModel delegate, CircuitBreaker circuitBreaker) {
        this.delegate = delegate;
        this.circuitBreaker = circuitBreaker;
    }

    /**
     * 覆写 chat(List&lt;ChatMessage&gt;) — 委托给 delegate.chat(messages) 而非走默认接口方法。
     * <p>
     * 关键：OpenAiChatModel 覆写了 chat() 来构建 OpenAiChatRequestParameters
     * （含 reasoningEffort 等字段）。若走 ChatModel 接口的默认 chat() 方法，
     * 会构建 DefaultChatRequestParameters，传给 delegate.doChat() 时触发 ClassCastException。
     */
    @Override
    public ChatResponse chat(List<ChatMessage> messages) {
        return circuitBreaker.executeSupplier(() -> delegate.chat(messages));
    }

    @Override
    public ChatResponse doChat(ChatRequest request) {
        return circuitBreaker.executeSupplier(() -> delegate.chat(request));
    }

    /**
     * 获取底层熔断器状态（用于监控/日志）
     */
    public CircuitBreaker.State getState() {
        return circuitBreaker.getState();
    }
}
