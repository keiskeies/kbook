package com.kbook.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * 共享线程池 Bean — 用于 SSE 流式响应等需要异步执行的场景。
 * 替代原先在 Controller 中 newCachedThreadPool() 导致的线程泄漏问题。
 */
@Configuration
public class AsyncExecutorConfig {

    /**
     * SSE 专用线程池 — 用于 TTS 流式响应等场景
     * - core=4: 平时少量活跃流
     * - max=32: 高峰期支持 32 路并发流
     * - queue=100: 缓冲突发请求
     * - keepAlive=60s: 空闲线程 60s 后回收
     */
    @Bean(name = "sseExecutor", destroyMethod = "shutdown")
    public Executor sseExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(32);
        executor.setQueueCapacity(100);
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("kbook-sse-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
