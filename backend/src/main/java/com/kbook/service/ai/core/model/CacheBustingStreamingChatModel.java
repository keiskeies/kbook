package com.kbook.service.ai.core.model;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;

import java.util.List;

/**
 * 流式缓存破坏装饰器。
 * <p>
 * 放在熔断器内侧：{@code StreamingSseHandler} 的重试每次都用原始消息重新调用模型，
 * 因此每次重试都会拿到一个新 nonce。
 */
public class CacheBustingStreamingChatModel implements StreamingChatModel {

    private final StreamingChatModel delegate;

    public CacheBustingStreamingChatModel(StreamingChatModel delegate) {
        this.delegate = delegate;
    }

    @Override
    public void chat(List<ChatMessage> messages, StreamingChatResponseHandler handler) {
        delegate.chat(CacheBustingSupport.inject(messages), handler);
    }

    @Override
    public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
        delegate.chat(CacheBustingSupport.inject(request), handler);
    }
}
