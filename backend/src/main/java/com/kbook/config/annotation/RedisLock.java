package com.kbook.config.annotation;

import java.lang.annotation.*;
import java.util.concurrent.TimeUnit;

/**
 * 分布式锁注解 — 基于 Redis 实现方法级互斥
 * <p>
 * 使用方式：
 * <pre>
 * @RedisLock(key = "'order:lock:' + #orderId", leaseTime = 30)
 * public void processOrder(Long orderId) { ... }
 * </pre>
 * 如果获取锁失败，方法将直接返回 null (或空集合/0)，不会执行方法体。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RedisLock {
    /**
     * 锁的 Key，支持 Spring EL 表达式
     * 例如: "'user:lock:' + #userId"
     */
    String key();

    /**
     * 锁自动释放时间（leaseTime），防止死锁
     * 默认 60
     */
    long leaseTime() default 60;

    /**
     * 时间单位
     * 默认 秒
     */
    TimeUnit timeUnit() default TimeUnit.SECONDS;
}
