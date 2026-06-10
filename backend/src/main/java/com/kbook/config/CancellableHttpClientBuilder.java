package com.kbook.config;

import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpClientBuilder;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 可取消的 HttpClientBuilder 包装器。
 * <p>
 * 包装真实的 {@link HttpClientBuilder}，在 {@link #build()} 时创建 {@link CancellableHttpClient}，
 * 并通过线程 ID 映射存储客户端引用。由于流式 HTTP 请求在 executor 线程执行，
 * 而 SSE 断开回调在 Tomcat 线程触发，因此使用线程 ID 做跨线程查找。
 * </p>
 */
public class CancellableHttpClientBuilder implements HttpClientBuilder {

    private final HttpClientBuilder delegate;

    /** 线程 ID → CancellableHttpClient 映射，支持跨线程取消 */
    private static final ConcurrentHashMap<Long, CancellableHttpClient> clientMap = new ConcurrentHashMap<>();

    public CancellableHttpClientBuilder(HttpClientBuilder delegate) {
        this.delegate = delegate;
    }

    /**
     * 取消指定线程上的活跃流式请求。
     * <p>
     * 关闭底层 HTTP InputStream，断开 TCP 连接，停止 AI 模型输出。
     * 调用后自动清理映射。
     * </p>
     *
     * @param threadId 执行流式请求的线程 ID
     */
    public static void cancelStream(long threadId) {
        CancellableHttpClient client = clientMap.remove(threadId);
        if (client != null) {
            client.cancel();
        }
    }

    /**
     * 清理指定线程的客户端引用（流式请求正常完成后调用）。
     */
    public static void clearStream(long threadId) {
        clientMap.remove(threadId);
    }

    @Override
    public Duration connectTimeout() {
        return delegate.connectTimeout();
    }

    @Override
    public HttpClientBuilder connectTimeout(Duration timeout) {
        delegate.connectTimeout(timeout);
        return this;
    }

    @Override
    public Duration readTimeout() {
        return delegate.readTimeout();
    }

    @Override
    public HttpClientBuilder readTimeout(Duration timeout) {
        delegate.readTimeout(timeout);
        return this;
    }

    @Override
    public HttpClient build() {
        CancellableHttpClient client = new CancellableHttpClient(delegate.build());
        clientMap.put(Thread.currentThread().getId(), client);
        return client;
    }
}
