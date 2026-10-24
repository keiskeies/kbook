package com.kbook.service.ai;

import org.slf4j.MDC;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/**
 * AI 用量归因上下文。
 * <p>
 * userId 由 {@code JwtAuthenticationFilter} 写入 MDC，并随 {@code sseExecutor} 的
 * MDC 快照自动传递到异步线程；图书/会话 ID 在提交任务前临时写入 MDC。
 * 模型装饰器在真正发起调用时读取快照，因此流式回调线程无需再持有上下文。
 */
public final class AiUsageContext {

    public static final String MDC_USER_ID = "userId";
    public static final String MDC_BOOK_ID = "aiBookId";
    public static final String MDC_SESSION_ID = "aiSessionId";

    private AiUsageContext() {
    }

    public record Target(Long userId, Long bookId, String sessionId) {
    }

    /** 读取当前线程的归因快照；无上下文时返回 null 字段。 */
    public static Target current() {
        return new Target(parseLong(MDC.get(MDC_USER_ID)),
                parseLong(MDC.get(MDC_BOOK_ID)),
                MDC.get(MDC_SESSION_ID));
    }

    /**
     * 提交任务前把图书/会话写入 MDC，让 MDC 快照随任务进入异步线程。
     * 提交完成后立即清理当前线程，避免污染后续请求。
     */
    public static Future<?> submit(ExecutorService executor, Long bookId, String sessionId, Runnable task) {
        return submitWithContext(executor, bookId, sessionId, () -> {
            task.run();
            return null;
        });
    }

    public static <T> Future<T> submit(ExecutorService executor, Long bookId, String sessionId, Callable<T> task) {
        return submitWithContext(executor, bookId, sessionId, task);
    }

    private static <T> Future<T> submitWithContext(ExecutorService executor, Long bookId,
                                                   String sessionId, Callable<T> task) {
        String previousBook = MDC.get(MDC_BOOK_ID);
        String previousSession = MDC.get(MDC_SESSION_ID);
        if (bookId != null) {
            MDC.put(MDC_BOOK_ID, String.valueOf(bookId));
        }
        if (sessionId != null && !sessionId.isBlank()) {
            MDC.put(MDC_SESSION_ID, sessionId);
        }
        try {
            return executor.submit(task);
        } finally {
            restore(MDC_BOOK_ID, previousBook);
            restore(MDC_SESSION_ID, previousSession);
        }
    }

    private static void restore(String key, String previous) {
        if (previous != null) {
            MDC.put(key, previous);
        } else {
            MDC.remove(key);
        }
    }

    private static Long parseLong(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
