package com.kbook.config;

import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpRequest;
import dev.langchain4j.http.client.SuccessfulHttpResponse;
import dev.langchain4j.http.client.sse.ServerSentEvent;
import dev.langchain4j.http.client.sse.ServerSentEventContext;
import dev.langchain4j.http.client.sse.ServerSentEventListener;
import dev.langchain4j.http.client.sse.ServerSentEventParser;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 可取消的 HttpClient 包装器。
 * <p>
 * 拦截流式请求的 {@link ServerSentEventParser#parse(InputStream, ServerSentEventListener)} 调用，
 * 追踪活跃的 {@link InputStream} 引用。调用 {@link #cancel()} 时关闭 InputStream，
 * 从而断开底层 TCP 连接，真正终止 AI 模型的流式输出。
 * </p>
 */
public class CancellableHttpClient implements HttpClient {

    private final HttpClient delegate;
    private final AtomicReference<InputStream> activeStream = new AtomicReference<>();

    public CancellableHttpClient(HttpClient delegate) {
        this.delegate = delegate;
    }

    /**
     * 非流式请求直接透传。
     */
    @Override
    public SuccessfulHttpResponse execute(HttpRequest request) {
        return delegate.execute(request);
    }

    /**
     * 流式请求：包装 parser 和 listener，拦截 InputStream 引用。
     */
    @Override
    public void execute(HttpRequest request, ServerSentEventParser parser, ServerSentEventListener listener) {
        // 包装 parser：在 parse() 被调用时捕获 InputStream
        ServerSentEventParser wrappedParser = (inputStream, eventListener) -> {
            activeStream.set(inputStream);
            try {
                parser.parse(inputStream, eventListener);
            } finally {
                activeStream.set(null);
            }
        };

        // 包装 listener：在 onError/onClose 时清理引用
        ServerSentEventListener wrappedListener = new ServerSentEventListener() {
            @Override
            public void onOpen(SuccessfulHttpResponse response) {
                listener.onOpen(response);
            }

            @Override
            public void onEvent(ServerSentEvent event, ServerSentEventContext context) {
                listener.onEvent(event, context);
            }

            @Override
            public void onEvent(ServerSentEvent event) {
                listener.onEvent(event);
            }

            @Override
            public void onError(Throwable error) {
                activeStream.set(null);
                listener.onError(error);
            }

            @Override
            public void onClose() {
                activeStream.set(null);
                listener.onClose();
            }
        };

        delegate.execute(request, wrappedParser, wrappedListener);
    }

    /**
     * 取消当前活跃的流式请求。
     * <p>
     * 关闭底层 InputStream，导致 TCP 连接断开，AI 模型停止生成。
     * 如果当前没有活跃的流式请求，此调用是空操作。
     * </p>
     */
    public void cancel() {
        InputStream stream = activeStream.getAndSet(null);
        if (stream != null) {
            try {
                stream.close();
            } catch (IOException e) {
                // 连接可能已被对端关闭，忽略
            }
        }
    }
}
