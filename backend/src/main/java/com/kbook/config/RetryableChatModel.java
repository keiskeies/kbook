package com.kbook.config;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.List;

/**
 * ChatModel 重试包装器 — 对 AI API 429 限速/5xx/网络错误自动重试
 * <p>
 * 使用指数退避策略：首次等待 1s，后续 2s、4s、8s... 最大间隔 30s，附带随机抖动。
 * 仅对可重试的异常（429/5xx/网络）进行重试；编程错误（NullPointer 等）直接抛出。
 */
@Slf4j
public class RetryableChatModel implements ChatModel {

    private final ChatModel delegate;
    private final int maxRetries;
    private final long baseDelayMs;

    public RetryableChatModel(ChatModel delegate) {
        this(delegate, 3, 1000L);
    }

    public RetryableChatModel(ChatModel delegate, int maxRetries, long baseDelayMs) {
        this.delegate = delegate;
        this.maxRetries = maxRetries;
        this.baseDelayMs = baseDelayMs;
    }

    @Override
    public ChatResponse chat(List<ChatMessage> messages) {
        int retries = 0;
        while (true) {
            try {
                return delegate.chat(messages);
            } catch (Exception e) {
                if (isRetryable(e) && retries < maxRetries) {
                    retries++;
                    long delay = computeDelay(retries);
                    log.warn("AI 请求失败可重试 (第{}/{}次重试)，等待 {}ms 后重试: {}",
                            retries, maxRetries, delay, e.getMessage());
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new AiRetryInterruptedException("AI 重试被中断", ie);
                    }
                } else {
                    throw e;
                }
            }
        }
    }

    /**
     * 判断异常是否值得重试：
     * - 429 限速（消息或反射拿到 statusCode）
     * - 5xx 服务端错误
     * - 网络层异常（IOException / SocketTimeout / UnknownHost）
     */
    private boolean isRetryable(Exception e) {
        if (isRateLimitError(e)) return true;
        if (isServerError(e)) return true;
        if (e instanceof SocketTimeoutException
                || e instanceof UnknownHostException
                || e instanceof IOException) {
            return true;
        }
        return false;
    }

    /**
     * 判断是否是 429 限速错误
     */
    private boolean isRateLimitError(Exception e) {
        if ("dev.langchain4j.model.openai.OpenAiHttpException".equals(e.getClass().getName())) {
            try {
                int code = (int) e.getClass().getMethod("statusCode").invoke(e);
                if (code == 429) return true;
            } catch (Exception ignored) {
                // 反射失败则走消息匹配
            }
        }
        String msg = e.getMessage();
        if (msg != null && (msg.contains("429") || msg.contains("Too Many Requests")
                || msg.contains("rate_limit") || msg.contains("Rate Limit"))) {
            return true;
        }
        return findInCause(e.getCause(), "429", "Too Many Requests", "rate_limit", "Rate Limit");
    }

    /**
     * 判断是否是 5xx 服务端错误
     */
    private boolean isServerError(Exception e) {
        String msg = e.getMessage();
        if (msg != null && (msg.contains(" 500 ") || msg.contains(" 502 ")
                || msg.contains(" 503 ") || msg.contains(" 504 ")
                || msg.contains("Internal Server Error")
                || msg.contains("Bad Gateway")
                || msg.contains("Service Unavailable")
                || msg.contains("Gateway Timeout"))) {
            return true;
        }
        return findInCause(e.getCause(), " 500 ", " 502 ", " 503 ", " 504 ",
                "Internal Server Error", "Bad Gateway", "Service Unavailable", "Gateway Timeout");
    }

    private boolean findInCause(Throwable cause, String... needles) {
        if (cause == null) return false;
        String msg = cause.getMessage();
        if (msg != null) {
            for (String needle : needles) {
                if (msg.contains(needle)) return true;
            }
        }
        return findInCause(cause.getCause(), needles);
    }

    /**
     * 指数退避 + 随机抖动
     */
    private long computeDelay(int retryCount) {
        long base = baseDelayMs * (1L << (retryCount - 1));
        long maxDelay = Math.min(base, 30_000L);
        double jitter = 0.75 + Math.random() * 0.5;
        return (long) (maxDelay * jitter);
    }
}
