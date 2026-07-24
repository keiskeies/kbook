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
 * | Bean 名称       | 用途                  | core | max | queue | 前缀           | MDC 传递方式          |
 * |-----------------|----------------------|------|-----|-------|---------------|----------------------|
 * | taskExecutor    | @Async 默认执行器      | 8    | 32  | 200   | kbook-async-  | TaskDecorator        |
 * | sseExecutor     | SSE 流式响应           | 4    | 32  | 100   | kbook-sse-    | MdcAwareThreadPoolExecutor |
 * | taskScheduler   | @Scheduled 定时调度    | 4    | -   | -     | kbook-sched-  | N/A（无请求上下文）    |
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
