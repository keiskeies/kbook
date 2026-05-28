package com.kbook.config;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * ChatModel 重试包装器 — 对 AI API 429 限速错误自动重试
 * <p>
 * 使用指数退避策略：首次等待 1s，后续 2s、4s、8s... 最大间隔 30s，附带随机抖动。
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
                if (isRateLimitError(e) && retries < maxRetries) {
                    retries++;
                    long delay = computeDelay(retries);
                    log.warn("AI API 429 限速 (第{}/{}次重试)，等待 {}ms 后重试: {}",
                            retries, maxRetries, delay, e.getMessage());
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("重试被中断", ie);
                    }
                } else {
                    throw e;
                }
            }
        }
    }

    /**
     * 判断是否是 429 限速错误
     */
    private boolean isRateLimitError(Exception e) {
        // 1) 检查 OpenAiHttpException
        if ("dev.langchain4j.model.openai.OpenAiHttpException".equals(e.getClass().getName())) {
            try {
                int code = (int) e.getClass().getMethod("statusCode").invoke(e);
                if (code == 429) return true;
            } catch (Exception ignored) {}
        }
        // 2) 检查消息内容
        String msg = e.getMessage();
        if (msg != null && (msg.contains("429") || msg.contains("Too Many Requests")
                || msg.contains("rate_limit") || msg.contains("Rate Limit"))) {
            return true;
        }
        // 3) 检查 cause 链
        return findRateLimitInCause(e.getCause());
    }

    private boolean findRateLimitInCause(Throwable cause) {
        if (cause == null) return false;
        String msg = cause.getMessage();
        if (msg != null && (msg.contains("429") || msg.contains("Too Many Requests")
                || msg.contains("rate_limit") || msg.contains("Rate Limit"))) {
            return true;
        }
        return findRateLimitInCause(cause.getCause());
    }

    /**
     * 指数退避 + 随机抖动
     */
    private long computeDelay(int retryCount) {
        long base = baseDelayMs * (1L << (retryCount - 1)); // 1s, 2s, 4s, 8s...
        long maxDelay = Math.min(base, 30_000L);
        // ±25% 随机抖动
        double jitter = 0.75 + Math.random() * 0.5;
        return (long) (maxDelay * jitter);
    }
}
