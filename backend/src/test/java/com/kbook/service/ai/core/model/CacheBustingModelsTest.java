package com.kbook.service.ai.core.model;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 缓存破坏层的契约。
 * <p>
 * 这层的作用是「让 provider 缓存必然 miss」，一旦退化（比如 nonce 挪到构建期生成），
 * 表现为「评测数字看着正常但全是缓存命中」，比直接报错更难发现，所以逐条钉住。
 */
class CacheBustingModelsTest {

    private static final List<ChatMessage> WITH_SYSTEM =
            List.of(SystemMessage.from("你是图书问答助手"), UserMessage.from("这本书讲了什么"));
    private static final List<ChatMessage> WITHOUT_SYSTEM = List.of(UserMessage.from("只有一句话"));

    private static String headOf(List<ChatMessage> messages) {
        ChatMessage first = messages.get(0);
        return first instanceof SystemMessage sm ? sm.text() : "<<非 system>>" + first;
    }

    private static String nonceOf(List<ChatMessage> messages) {
        String head = headOf(messages);
        int end = head.indexOf(']');
        return end < 0 ? null : head.substring(0, end + 1);
    }

    // ==================== 注入位置 ====================

    @Test
    @DisplayName("首条是 SystemMessage → nonce 拼在它最前，system 段数不变")
    void prependsToExistingSystemMessage() {
        CapturingChatModel delegate = new CapturingChatModel();
        new CacheBustingChatModel(delegate).chat(WITH_SYSTEM);

        List<ChatMessage> sent = delegate.received.get(0);
        assertThat(sent).hasSameSizeAs(WITH_SYSTEM);
        assertThat(headOf(sent)).startsWith(CacheBustingSupport.MARKER_PREFIX);
        assertThat(headOf(sent)).endsWith("你是图书问答助手");
    }

    @Test
    @DisplayName("没有 SystemMessage → 在 0 位补一条只含 nonce 的 system")
    void insertsSystemWhenAbsent() {
        CapturingChatModel delegate = new CapturingChatModel();
        new CacheBustingChatModel(delegate).chat(WITHOUT_SYSTEM);

        List<ChatMessage> sent = delegate.received.get(0);
        assertThat(sent.get(0)).isInstanceOf(SystemMessage.class);
        assertThat(headOf(sent)).startsWith(CacheBustingSupport.MARKER_PREFIX);
        assertThat(sent.get(1)).isEqualTo(WITHOUT_SYSTEM.get(0));
    }

    @Test
    @DisplayName("连续两次调用的 nonce 不同（构建期生成等于没破坏）")
    void nonceDiffersPerCall() {
        CapturingChatModel delegate = new CapturingChatModel();
        CacheBustingChatModel model = new CacheBustingChatModel(delegate);
        model.chat(WITH_SYSTEM);
        model.chat(WITH_SYSTEM);

        assertThat(nonceOf(delegate.received.get(0))).isNotEqualTo(nonceOf(delegate.received.get(1)));
    }

    @Test
    @DisplayName("ChatRequest 入口也注入，且 temperature/topP/maxOutputTokens 原样保留")
    void preservesRequestParameters() {
        CapturingChatModel delegate = new CapturingChatModel();
        ChatRequest request = ChatRequest.builder()
                .messages(WITH_SYSTEM).temperature(0.3).topP(0.9).maxOutputTokens(128).build();

        new CacheBustingChatModel(delegate).doChat(request);

        assertThat(headOf(delegate.received.get(0))).startsWith(CacheBustingSupport.MARKER_PREFIX);
        assertThat(delegate.lastRequest.temperature()).isEqualTo(0.3);
        assertThat(delegate.lastRequest.topP()).isEqualTo(0.9);
        assertThat(delegate.lastRequest.maxOutputTokens()).isEqualTo(128);
    }

    @Test
    @DisplayName("不修改调用方传入的消息列表")
    void doesNotMutateCallerList() {
        List<ChatMessage> caller = new ArrayList<>(WITH_SYSTEM);
        new CacheBustingChatModel(new CapturingChatModel()).chat(caller);

        assertThat(caller).isEqualTo(WITH_SYSTEM);
        assertThat(caller).hasSize(2);
    }

    // ==================== 流式 ====================

    @Test
    @DisplayName("流式 chat(List) 与 doChat(ChatRequest) 两个入口都注入")
    void streamingCoversBothEntries() {
        CapturingStreamingChatModel delegate = new CapturingStreamingChatModel();
        CacheBustingStreamingChatModel model = new CacheBustingStreamingChatModel(delegate);

        model.chat(WITH_SYSTEM, delegate.handler());
        model.doChat(ChatRequest.builder().messages(WITH_SYSTEM).build(), delegate.handler());

        assertThat(delegate.received).hasSize(2);
        assertThat(delegate.received).allSatisfy(messages ->
                assertThat(headOf(messages)).startsWith(CacheBustingSupport.MARKER_PREFIX));
        assertThat(nonceOf(delegate.received.get(0))).isNotEqualTo(nonceOf(delegate.received.get(1)));
    }

    // ==================== 重试内侧 ====================

    @Test
    @DisplayName("429 重试每次重新调用 → 每次都是新 nonce（nonce 层必须在重试内侧）")
    void retryGetsFreshNonceEachAttempt() {
        CapturingChatModel delegate = new CapturingChatModel();
        delegate.failures.add(new RuntimeException("429 Too Many Requests"));
        ChatModel chain = new RetryableChatModel(new CacheBustingChatModel(delegate), 1, 1L);

        chain.chat(WITH_SYSTEM);

        assertThat(delegate.received).hasSize(2);
        assertThat(nonceOf(delegate.received.get(0))).isNotEqualTo(nonceOf(delegate.received.get(1)));
    }

    @Test
    @DisplayName("doChat 入口走重试时同样每次新 nonce")
    void retryOnDoChatEntryGetsFreshNonce() {
        CapturingChatModel delegate = new CapturingChatModel();
        delegate.failures.add(new RuntimeException("429 Too Many Requests"));
        ChatModel chain = new RetryableChatModel(new CacheBustingChatModel(delegate), 1, 1L);

        chain.chat(ChatRequest.builder().messages(WITH_SYSTEM).build());

        assertThat(delegate.received).hasSize(2);
        assertThat(nonceOf(delegate.received.get(0))).isNotEqualTo(nonceOf(delegate.received.get(1)));
    }

    // ==================== 桩 ====================

    /** 记录每次收到的消息列表；failures 里的异常按次序抛出 */
    private static class CapturingChatModel implements ChatModel {
        final List<List<ChatMessage>> received = new ArrayList<>();
        final Deque<RuntimeException> failures = new ArrayDeque<>();
        ChatRequest lastRequest;

        @Override
        public ChatResponse chat(List<ChatMessage> messages) {
            // 先记录再抛错：重试用例关心的是「每一次实际发出去的消息长什么样」
            received.add(new ArrayList<>(messages));
            maybeFail();
            return ChatResponse.builder().aiMessage(AiMessage.from("ok")).build();
        }

        @Override
        public ChatResponse doChat(ChatRequest request) {
            this.lastRequest = request;
            return chat(request.messages());
        }

        private void maybeFail() {
            RuntimeException failure = failures.pollFirst();
            if (failure != null) {
                throw failure;
            }
        }
    }

    private static class CapturingStreamingChatModel implements StreamingChatModel {
        final List<List<ChatMessage>> received = new ArrayList<>();

        StreamingChatResponseHandler handler() {
            return new StreamingChatResponseHandler() {
                @Override
                public void onCompleteResponse(ChatResponse response) {
                }

                @Override
                public void onError(Throwable error) {
                    throw new AssertionError(error);
                }
            };
        }

        @Override
        public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
            received.add(new ArrayList<>(request.messages()));
            handler.onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from("ok")).build());
        }
    }
}
