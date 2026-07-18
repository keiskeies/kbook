package com.kbook.config;

import java.util.concurrent.*;

/**
 * MDC 感知的 ThreadPoolExecutor — 自动传递主线程 MDC 到子线程。
 * <p>
 * 重写 {@link #execute} 和所有 {@link #submit} 重载，在提交时捕获 MDC 快照，
 * 在子线程执行前恢复、执行后清理。适用于 {@code sseExecutor} 等原生线程池
 * （{@link ThreadPoolTaskExecutor} 可直接用 {@link MdcTaskDecorator}）。
 * <p>
 * 业务代码无需任何改动，所有 {@code execute}/{@code submit} 调用自动包装。
 *
 * @author James Chen right_way@foxmail.com
 */
public class MdcAwareThreadPoolExecutor extends ThreadPoolExecutor {

    public MdcAwareThreadPoolExecutor(int corePoolSize, int maximumPoolSize, long keepAliveSeconds,
                                      BlockingQueue<Runnable> workQueue, ThreadFactory threadFactory,
                                      RejectedExecutionHandler handler) {
        super(corePoolSize, maximumPoolSize, keepAliveSeconds, TimeUnit.SECONDS, workQueue, threadFactory, handler);
    }

    @Override
    public void execute(Runnable command) {
        super.execute(MdcTaskDecorator.wrap(command));
    }

    @Override
    public Future<?> submit(Runnable task) {
        return super.submit(MdcTaskDecorator.wrap(task));
    }

    @Override
    public <T> Future<T> submit(Runnable task, T result) {
        return super.submit(MdcTaskDecorator.wrap(task), result);
    }

    @Override
    public <T> Future<T> submit(Callable<T> task) {
        return super.submit(MdcTaskDecorator.wrap(task));
    }
}
