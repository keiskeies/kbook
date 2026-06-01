package com.kbook.config;

/**
 * AI 重试被打断异常 — 包装 InterruptedException 并保留中断状态
 */
public class AiRetryInterruptedException extends RuntimeException {

    public AiRetryInterruptedException(String message, Throwable cause) {
        super(message, cause);
    }
}
