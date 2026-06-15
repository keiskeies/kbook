package com.kbook.config;

import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;

/**
 * 统一线程池配置
 * <p>
 * 管理所有异步/调度/SSE 线程池，避免散落各处导致参数不一致和线程泄漏。
 *
 * <pre>
 * | Bean 名称       | 用途                  | core | max | queue | 前缀           |
 * |-----------------|----------------------|------|-----|-------|---------------|
 * | taskExecutor    | @Async 默认执行器      | 8    | 32  | 200   | kbook-async-  |
 * | sseExecutor     | SSE 流式响应           | 4    | 32  | 100   | kbook-sse-    |
 * | taskScheduler   | @Scheduled 定时调度    | 4    | -   | -     | kbook-sched-  |
 * </pre>
 */
@Configuration
public class AsyncSchedulingConfig implements AsyncConfigurer, SchedulingConfigurer {

    // ==================== @Async 默认执行器 ====================

    @Bean(name = "taskExecutor")
    @Override
    public Executor getAsyncExecutor() {
        return buildTaskExecutor("kbook-async-", 8, 32, 200);
    }

    // ==================== SSE 流式响应执行器 ====================

    @Bean(name = "sseExecutor", destroyMethod = "shutdown")
    public java.util.concurrent.ThreadPoolExecutor sseExecutor() {
        return buildTaskExecutor("kbook-sse-", 4, 32, 100).getThreadPoolExecutor();
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
