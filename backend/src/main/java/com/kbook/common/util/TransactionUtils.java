package com.kbook.common.util;

import org.slf4j.MDC;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;

/**
 * <p>
 * 事务工具类
 * </p>
 *
 * @author James Chen right_way@foxmail.com
 * @since 2025/12/11 16:47
 */
public class TransactionUtils {

    /**
     * 在事务提交后执行回调函数
     *
     * @param callback 事务提交后需要执行的回调函数，如果当前没有活跃事务则立即执行
     */
    public static void afterCommit(Runnable callback) {

        // 检查当前是否存在活跃的事务
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            // 如果存在事务，注册一个事务同步器，在事务提交后执行回调
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    callback.run();
                }
            });
        } else {
            // 如果没有事务，立即执行
            callback.run();
        }
    }

    /**
     * 在事务提交后异步执行回调函数
     *
     * @param callback 事务提交后需要执行的回调函数
     * @param executor 用于执行回调函数的线程池
     */
    public static void afterCommitAsync(Runnable callback, Executor executor) {
        // 检查当前是否存在活跃的事务
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            // 注册事务同步回调，在事务提交后通过指定的线程池执行回调函数
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    executor.execute(wrap(callback));
                }
            });
        } else {
            // 如果没有事务，立即执行
            callback.run();
        }
    }

    /**
     * 包装 Runnable，传递 MDC 上下文到异步线程，防止日志追踪信息丢失
     *
     * @param runnable 原始 Runnable
     * @return 包装后的 Runnable
     */
    private static Runnable wrap(Runnable runnable) {
        Map<String, String> mdcContext = MDC.getCopyOfContextMap();
        return () -> {
            if (mdcContext != null) {
                MDC.setContextMap(mdcContext);
            }
            try {
                runnable.run();
            } finally {
                MDC.clear(); // 防止线程池污染
            }
        };
    }

    /**
     * 包装 Callable（如果你用 submit）
     *
     * @param <V>      返回值类型
     * @param callable 要包装的Callable
     * @return 包装后的Callable
     */
    private static <V> Callable<V> wrap(Callable<V> callable) {
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
