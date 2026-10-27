package com.kbook.config;

import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 统一线程池配置
 * <p>
 * 管理所有异步/调度/SSE 线程池，避免散落各处导致参数不一致和线程泄漏。
 * 所有线程池均配置 MDC 上下文传递，确保子线程日志中 {@code [clientIp] [userId]} 不丢失。
 *
 * <pre>
 * | Bean 名称          | 用途                  | core | max | queue | 前缀              | MDC 传递方式          |
 * |--------------------|----------------------|------|-----|-------|-------------------|----------------------|
 * | taskExecutor       | @Async 默认执行器      | 8    | 32  | 200   | kbook-async-      | TaskDecorator        |
 * | sseExecutor        | SSE 流式响应           | 4    | 32  | 100   | kbook-sse-        | MdcAwareThreadPoolExecutor |
 * | behaviorExecutor   | 行为画像抽取            | 2    | 4   | 50    | kbook-behavior-   | MdcAwareThreadPoolExecutor |
 * | qaPrefetchExecutor | 列表流水线逐条预取       | 8    | 8   | 64    | kbook-qa-prefetch-| MdcAwareThreadPoolExecutor |
 * | listRefineExecutor | 列表型 LLM 精筛（分批）  | 8    | 8   | 64    | kbook-list-refine-| MdcAwareThreadPoolExecutor |
 * | taskScheduler      | @Scheduled 定时调度    | 4    | -   | -     | kbook-sched-      | N/A（无请求上下文）    |
 * </pre>
 */
@Configuration
public class AsyncSchedulingConfig implements AsyncConfigurer, SchedulingConfigurer {

    // ==================== @Async 默认执行器 ====================

    @Bean(name = "taskExecutor")
    @Override
    public Executor getAsyncExecutor() {
        ThreadPoolTaskExecutor executor = buildTaskExecutor("kbook-async-", 8, 32, 200);
        // 自动传递 MDC 到 @Async 子线程
        executor.setTaskDecorator(new MdcTaskDecorator());
        return executor;
    }

    // ==================== SSE 流式响应执行器 ====================

    @Bean(name = "sseExecutor", destroyMethod = "shutdown")
    public ThreadPoolExecutor sseExecutor() {
        // 使用 MDC 感知的 ThreadPoolExecutor，所有 execute/submit 自动传递 MDC
        // 线程名用 AtomicLong 计数器，保持 kbook-sse-1, kbook-sse-2 的递增格式
        java.util.concurrent.atomic.AtomicLong seq = new java.util.concurrent.atomic.AtomicLong(0);
        return new MdcAwareThreadPoolExecutor(
                4, 32, 60,
                new ArrayBlockingQueue<>(100),
                r -> {
                    Thread t = new Thread(r);
                    t.setName("kbook-sse-" + seq.incrementAndGet());
                    t.setDaemon(false);
                    return t;
                },
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }

    // ==================== 行为画像抽取执行器 ====================

    /**
     * 行为画像异步抽取专用线程池。
     * <p>独立于 {@code sseExecutor}，避免画像抽取的 LLM 调用阻塞 SSE 流式响应。
     * <br>核心 2 / 最大 4 / 队列 50：抽取是低频任务，单用户触发即可，不需要大池。
     */
    @Bean(name = "behaviorExecutor", destroyMethod = "shutdown")
    public ThreadPoolExecutor behaviorExecutor() {
        java.util.concurrent.atomic.AtomicLong seq = new java.util.concurrent.atomic.AtomicLong(0);
        return new MdcAwareThreadPoolExecutor(
                2, 4, 60,
                new ArrayBlockingQueue<>(50),
                r -> {
                    Thread t = new Thread(r);
                    t.setName("kbook-behavior-" + seq.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }

    // ==================== 图书问答流水线执行器 ====================

    /**
     * 列表展开流水线的逐条检索预取（prefetch）线程池。
     * <p>独立于 {@code sseExecutor}：预取是 IO 密集的向量查询，与 SSE 流式生成隔离，
     * 防止 {@code waitForContentEmbedding} 一类含 sleep 的任务占满池导致问答整体排队。
     * <br>核心 8 / 最大 8 / 队列 64：N≤8 条并行一轮跑完；AbortPolicy——
     * 队列满说明并发会话超限，快速失败由调用方兜底（该条退化为关键词检索空结果）。
     */
    @Bean(name = "qaPrefetchExecutor", destroyMethod = "shutdown")
    public ThreadPoolExecutor qaPrefetchExecutor() {
        java.util.concurrent.atomic.AtomicLong seq = new java.util.concurrent.atomic.AtomicLong(0);
        return new MdcAwareThreadPoolExecutor(
                8, 8, 60,
                new ArrayBlockingQueue<>(64),
                r -> {
                    Thread t = new Thread(r);
                    t.setName("kbook-qa-prefetch-" + seq.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    /**
     * 列表型问题 LLM 精筛线程池。
     * <p>独立于 {@code qaPrefetchExecutor}：精筛是批量 LLM 调用（每批 15 chunks），
     * 与流水线逐条预取同时发生会互相饿死；2026-10-26 运行时实测串行精筛 15 批耗时 97.5s，
     * 并行后单轮即可完成。
     * <br>核心 8 / 最大 8 / 队列 64；AbortPolicy——提交点捕获拒绝异常并按「保守保留该批」兜底。
     */
    @Bean(name = "listRefineExecutor", destroyMethod = "shutdown")
    public ThreadPoolExecutor listRefineExecutor() {
        java.util.concurrent.atomic.AtomicLong seq = new java.util.concurrent.atomic.AtomicLong(0);
        return new MdcAwareThreadPoolExecutor(
                8, 8, 60,
                new ArrayBlockingQueue<>(64),
                r -> {
                    Thread t = new Thread(r);
                    t.setName("kbook-list-refine-" + seq.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    /**
     * 回答条目化（outline 异步生成/回填）线程池。
     * <p>纯 fire-and-forget：失败或被丢弃不影响主流程（追问轮取不到 outline 会现场条目化）。
     * <br>核心 2 / 最大 2 / 队列 20；AbortPolicy——提交点对 RejectedExecutionException 静默吞掉。
     */
    @Bean(name = "qaOutlineExecutor", destroyMethod = "shutdown")
    public ThreadPoolExecutor qaOutlineExecutor() {
        java.util.concurrent.atomic.AtomicLong seq = new java.util.concurrent.atomic.AtomicLong(0);
        return new MdcAwareThreadPoolExecutor(
                2, 2, 60,
                new ArrayBlockingQueue<>(20),
                r -> {
                    Thread t = new Thread(r);
                    t.setName("kbook-qa-outline-" + seq.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    // ==================== @Scheduled 调度器 ====================

    @Bean(name = "taskScheduler")
    public ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(4);
        scheduler.setThreadNamePrefix("kbook-sched-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(30);
        scheduler.initialize();
        return scheduler;
    }

    // ==================== 公共构建方法 ====================

    private ThreadPoolTaskExecutor buildTaskExecutor(String prefix, int core, int max, int queue) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(core);
        executor.setMaxPoolSize(max);
        executor.setQueueCapacity(queue);
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix(prefix);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }

    // ==================== 异常处理 & 调度注册 ====================

    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return (ex, method, params) -> {
            org.slf4j.LoggerFactory.getLogger(AsyncSchedulingConfig.class)
                    .error("Async method {} threw exception: {}", method.getName(), ex.getMessage(), ex);
        };
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.setTaskScheduler(taskScheduler());
    }
}
