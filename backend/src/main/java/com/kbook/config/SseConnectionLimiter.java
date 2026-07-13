package com.kbook.config;

import com.kbook.common.util.SseHelper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * SSE 连接限流器 — 两层限流防止线程池耗尽
 * <p>
 * 设计：
 * <ul>
 *   <li>全局上限 50：sseExecutor max=32，留余量给非 SSE 异步任务</li>
 *   <li>用户上限 3：防止单个用户开多 tab 占满连接（辩论+聊天+TTS 场景）</li>
 * </ul>
 * <p>
 * 使用方式：
 * <pre>{@code
 *   SseEmitter emitter = sseLimiter.withLimit(userId, () -> service.streamXxx(userId, ...));
 * }</pre>
 * <p>
 * 被拒绝时返回带错误事件的 SseEmitter，客户端收到 "当前在线连接数过多" 提示。
 */
@Slf4j
@Component
public class SseConnectionLimiter {

    /** 全局并发 SSE 上限 */
    private static final int GLOBAL_LIMIT = 50;

    /** 每用户并发 SSE 上限 */
    private static final int USER_LIMIT = 3;

    private final Semaphore globalLimit = new Semaphore(GLOBAL_LIMIT);
    private final ConcurrentMap<Long, Semaphore> userLimits = new ConcurrentHashMap<>();

    /**
     * 尝试获取 SSE 连接许可
     *
     * @param userId 用户 ID
     * @return 释放回调（必须调用），null 表示被拒绝
     */
    public Runnable tryAcquire(Long userId) {
        if (userId == null) return null;

        if (!globalLimit.tryAcquire()) {
            log.warn("SSE 全局连接上限已达 {}，拒绝新连接（userId={}）", GLOBAL_LIMIT, userId);
            return null;
        }

        Semaphore userSem = userLimits.computeIfAbsent(userId, k -> new Semaphore(USER_LIMIT));
        if (!userSem.tryAcquire()) {
            globalLimit.release();
            log.warn("用户 {} SSE 连接数达上限 {}，拒绝新连接", userId, USER_LIMIT);
            return null;
        }

        // 幂等释放 — 防止 onCompletion + onError 重复触发
        AtomicBoolean released = new AtomicBoolean(false);
        return () -> {
            if (released.compareAndSet(false, true)) {
                userSem.release();
                globalLimit.release();
            }
        };
    }

    /**
     * 带限流的 SSE 执行器
     * <p>
     * 流程：tryAcquire → 调用 supplier 创建 emitter → attach 释放回调
     * 被拒绝时返回带错误事件的 emitter，supplier 不执行。
     *
     * @param userId   用户 ID
     * @param supplier SSE emitter 创建逻辑（调用 Service 层）
     * @return SseEmitter 实例
     */
    public SseEmitter withLimit(Long userId, Supplier<SseEmitter> supplier) {
        Runnable release = tryAcquire(userId);
        if (release == null) {
            SseEmitter emitter = new SseEmitter();
            SseHelper.sendErrorAndComplete(emitter, "当前在线连接数过多，请稍后重试");
            return emitter;
        }
        try {
            SseEmitter emitter = supplier.get();
            emitter.onCompletion(release);
            emitter.onTimeout(release);
            emitter.onError(e -> release.run());
            return emitter;
        } catch (Exception e) {
            release.run();
            throw e;
        }
    }

    /**
     * 获取当前全局可用 SSE 连接数（用于监控）
     */
    public int availableGlobal() {
        return globalLimit.availablePermits();
    }
}
