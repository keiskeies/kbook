package com.kbook.config;

import lombok.extern.slf4j.Slf4j;
import jakarta.annotation.PostConstruct;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * AI 配置缓存失效协调器 — 解决多副本（k8s {@code replicas>=2}）下本地缓存不一致。
 * <p>
 * 工作机制：
 * <ol>
 *   <li>每个持有 AI 配置相关缓存的组件（场景解析 / 模型实例 / Assistant）
 *       通过 {@link #register(Runnable)} 注册本地失效回调。</li>
 *   <li>任何副本修改 AI 配置后调用 {@link #bumpVersion()}：
 *       对 Redis 全局版本号执行 {@code INCR}（其他副本可感知），并立即触发本副本的回调。</li>
 *   <li>每个副本每 {@code kbook.ai.cache.version-poll-interval-ms}（默认 1s）轮询一次
 *       Redis 版本号，发现变化即触发本地回调 — 跨副本失效延迟约 1s。</li>
 * </ol>
 * <p>
 * 容错：Redis 不可用时仅退化为本副本立即失效（跨副本传播停止），不影响读路径；
 * 本地缓存本身带 TTL 兜底，最终一致。
 */
@Slf4j
@Component
public class AiConfigCacheInvalidator {

    /** Redis 中的全局配置版本号键 */
    private static final String VERSION_KEY = "kbook:ai:config:version";

    /** 版本号缺省基线（键不存在时视为 0，任何 INCR 产生的 1 都会被识别为变更） */
    private static final String BASELINE_VERSION = "0";

    private final StringRedisTemplate redisTemplate;

    /** 已注册的本地失效回调（线程安全） */
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    /** 本副本最近一次看到的 Redis 版本号 */
    private volatile String lastSeenVersion = BASELINE_VERSION;

    public AiConfigCacheInvalidator(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 启动时读取版本号建立基线（此刻本地缓存均为空，无需触发失效）。
     * Redis 不可用时保持缺省基线，由首次轮询自行对齐。
     */
    @PostConstruct
    void initBaseline() {
        try {
            String current = redisTemplate.opsForValue().get(VERSION_KEY);
            if (current != null) {
                lastSeenVersion = current;
            }
        } catch (Exception e) {
            log.warn("AI 配置版本号基线读取失败（Redis 不可用），将由轮询自行对齐: {}", e.getMessage());
        }
    }

    /**
     * 注册本地缓存失效回调（在配置变更或检测到其他副本变更时执行）。
     *
     * @param listener 失效回调（应为幂等的清空操作）
     */
    public void register(Runnable listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    /**
     * AI 配置写入后调用：递增 Redis 全局版本号并立即清空本副本缓存。
     * 其他副本通过 {@link #pollRemoteVersion()} 轮询感知。
     */
    public void bumpVersion() {
        Long newVersion = null;
        try {
            newVersion = redisTemplate.opsForValue().increment(VERSION_KEY);
        } catch (Exception e) {
            log.warn("AI 配置版本号递增失败（Redis 不可用），仅本副本缓存立即失效: {}", e.getMessage());
        }
        if (newVersion != null) {
            lastSeenVersion = String.valueOf(newVersion);
        }
        fire("配置写入");
    }

    /**
     * 定时轮询 Redis 版本号，感知其他副本的配置变更。
     * 基线为启动时读取的值（键不存在则为 0），版本变化即触发本地失效。
     */
    @Scheduled(
            fixedDelayString = "${kbook.ai.cache.version-poll-interval-ms:1000}",
            initialDelayString = "${kbook.ai.cache.version-poll-initial-delay-ms:5000}")
    public void pollRemoteVersion() {
        String current;
        try {
            current = redisTemplate.opsForValue().get(VERSION_KEY);
        } catch (Exception e) {
            log.debug("AI 配置版本号轮询失败（Redis 不可用），跳过本轮: {}", e.getMessage());
            return;
        }
        if (current == null) {
            return;
        }
        String previous = lastSeenVersion;
        if (previous.equals(current)) {
            return;
        }
        lastSeenVersion = current;
        fire("检测到其他副本的 AI 配置变更");
    }

    private void fire(String reason) {
        if (listeners.isEmpty()) {
            return;
        }
        log.info("AI 配置缓存失效: {}（监听器 {} 个）", reason, listeners.size());
        for (Runnable listener : listeners) {
            try {
                listener.run();
            } catch (Exception e) {
                log.warn("AI 配置缓存失效回调异常: {}", e.getMessage());
            }
        }
    }
}
