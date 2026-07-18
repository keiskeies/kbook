package com.kbook.config;

import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;

import java.util.Map;
import java.util.concurrent.Callable;

/**
 * MDC 上下文传递装饰器 — 将主线程的 MDC 自动传递到子线程。
 * <p>
 * 用于 {@link org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor#setTaskDecorator(TaskDecorator)}
 * 或自定义线程池的 {@code execute}/{@code submit} 重写，确保异步线程中
 * {@code %X{clientIp}} / {@code %X{userId}} 等日志变量不丢失。
 * <p>
 * 原理：在提交任务时（主线程）捕获 MDC 快照，在执行任务前（子线程）恢复，
 * 执行后清理以防线程池污染。
 *
 * @author James Chen right_way@foxmail.com
 */
public final class MdcTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable runnable) {
        return wrap(runnable);
    }

    /**
     * 包装 Runnable，传递当前线程的 MDC 到异步线程。
     */
    public static Runnable wrap(Runnable runnable) {
        Map<String, String> mdcContext = MDC.getCopyOfContextMap();
        return () -> {
            if (mdcContext != null) {
                MDC.setContextMap(mdcContext);
            }
            try {
                runnable.run();
            } finally {
                MDC.clear();
            }
        };
    }

    /**
     * 包装 Callable，传递当前线程的 MDC 到异步线程。
     */
    public static <V> Callable<V> wrap(Callable<V> callable) {
        Map<String, String> mdcContext = MDC.getCopyOfContextMap();
        return () -> {
            if (mdcContext != null) {
                MDC.setContextMap(mdcContext);
            }
            try {
                return callable.call();
            } finally {
                MDC.clear();
            }
        };
    }
}
