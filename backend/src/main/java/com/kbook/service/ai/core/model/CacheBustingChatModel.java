package com.kbook.service.ai.core.model;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

import java.util.List;

/**
 * 非流式缓存破坏装饰器 — 每次调用在最前面注入新 nonce 后交给实际模型。
 * <p>
 * 两个入口都覆盖，且都调 {@code delegate.chat(...)} 而不是 {@code delegate.doChat(...)}：
 * {@code OpenAiChatModel} 覆写了 {@code chat()} 来构建自己的参数类型，
 * 直接转 {@code doChat} 会把 {@code DefaultChatRequestParameters} 塞进去触发 ClassCastException。
 */
public class CacheBustingChatModel implements ChatModel {

    private final ChatModel delegate;

    public CacheBustingChatModel(ChatModel delegate) {
        this.delegate = delegate;
    }

    @Override
    public ChatResponse chat(List<ChatMessage> messages) {
        return delegate.chat(CacheBustingSupport.inject(messages));
    }

    @Override
    public ChatResponse doChat(ChatRequest request) {
        return delegate.chat(CacheBustingSupport.inject(request));
    }
}
