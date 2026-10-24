package com.kbook.service.ai;

import com.kbook.common.util.SseHelper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.nio.charset.StandardCharsets;

/**
 * 3 分钟速读缓存 — 一个用户一个 Redis Hash，field = 图书 id，value = 指纹头 + 速读正文。
 *
 * <p>为什么按用户聚合而不是按 userId+bookId 散列：
 * <ul>
 *   <li>用户画像更新时只需 {@code DEL} 一个 key，该用户所有书的速读缓存立即整体失效</li>
 *   <li>Hash 的 field 固定为 bookId，画像/书目变化不会产生孤儿 key，因此不需要 TTL 兜底</li>
 * </ul>
 *
 * <p>失效策略：
 * <ul>
 *   <li>用户画像（稳定字段）变化 → {@code updateTraits} 提交后 {@link #invalidateUser} 直接删 key</li>
 *   <li>书目内容（书名/简介/标签/章节摘要）变化 → 值内的书籍指纹不匹配，读取时视为未命中并覆盖写</li>
 *   <li>画像指纹同时保存在值内，作为漏挂失效钩子时的兜底</li>
 *   <li>mood/阅读意图不计入指纹（更新频繁，避免每次改心情都整书重生成）</li>
 * </ul>
 *
 * <p>命中后按行切块流式回放，SSE 协议与真实生成完全一致，前端无感。
 * Redis 故障时全部降级为未命中/无锁，不影响主流程。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SpeedReadCache {

    /** v1 = 提示词/输出格式/缓存结构版本，升级时递增即可整体失效 */
    private static final String KEY_PREFIX = "kbook:speed-read:v1:";
    private static final String LOCK_PREFIX = "kbook:lock:speed-read:v1:";
    private static final String ANONYMOUS = "anon";

    private static final long LOCK_WAIT_MS = 2_000L;
    private static final long LOCK_POLL_MS = 200L;
    private static final java.time.Duration LOCK_TTL = java.time.Duration.ofSeconds(300);

    /** 回放分块大小（字符）。优先在换行处断开，保证前端按行解析不丢内容 */
    private static final int CHUNK_CHARS = 48;
    /** 回放目标总时长，用于倒推每块延迟，避免长内容回放拖太久 */
    private static final long REPLAY_TARGET_MS = 2_500L;

    /** 值格式：bookFp|profileFp|content（指纹为 16 位 hex，不含分隔符） */
    private static final char SEP = '|';

    private final StringRedisTemplate redis;

    private record CacheEntry(String bookFp, String profileFp, String content) {
    }

    /**
     * 计算内容指纹（截断到 16 位，控制 value 头部长度）。
     */
    public String fingerprint(String text) {
        String raw = text != null ? text : "";
        return DigestUtils.md5DigestAsHex(raw.getBytes(StandardCharsets.UTF_8)).substring(0, 16);
    }

    /**
     * 读取缓存，任一指纹不匹配或数据损坏都视为未命中。
     */
    public String get(Long bookId, Long userId, String bookFp, String profileFp) {
        try {
            Object raw = redis.opsForHash().get(buildKey(userId), String.valueOf(bookId));
            if (raw == null) return null;

            CacheEntry entry = decode(raw.toString());
            if (entry == null) {
                log.warn("[SPEED-READ-CACHE] 缓存格式异常，按未命中处理: bookId={}, userId={}", bookId, userId);
                return null;
            }
            if (!entry.bookFp().equals(bookFp) || !entry.profileFp().equals(profileFp)) {
                log.info("[SPEED-READ-CACHE] 指纹变化，缓存失效: bookId={}, userId={}", bookId, userId);
                return null;
            }
            if (entry.content().isBlank()) return null;
            return entry.content();
        } catch (Exception e) {
            log.warn("[SPEED-READ-CACHE] 读取失败，降级为未命中: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 写入缓存（同一用户同一本书只保留最新一份）。
     */
    public void put(Long bookId, Long userId, String bookFp, String profileFp, String content) {
        if (content == null || content.isBlank()) return;
        try {
            redis.opsForHash().put(buildKey(userId), String.valueOf(bookId),
                    encode(bookFp, profileFp, content));
            log.info("[SPEED-READ-CACHE] 写入 userId={} bookId={} ({}字符)",
                    userId, bookId, content.length());
        } catch (Exception e) {
            log.warn("[SPEED-READ-CACHE] 写入失败: {}", e.getMessage());
        }
    }

    /**
     * 用户画像变更后清空该用户全部速读缓存（一次 DEL）。
     */
    public void invalidateUser(Long userId) {
        if (userId == null) return;
        try {
            Boolean deleted = redis.delete(buildKey(userId));
            log.info("[SPEED-READ-CACHE] 画像更新，清空用户速读缓存: userId={}, deleted={}", userId, deleted);
        } catch (Exception e) {
            log.warn("[SPEED-READ-CACHE] 清空用户缓存失败: userId={}, error={}", userId, e.getMessage());
        }
    }

    /**
     * 尝试获取生成锁（击穿保护）。Redis 异常时降级为无锁。
     *
     * @return true 表示本次请求负责生成并写缓存
     */
    public boolean tryLock(Long bookId, Long userId) {
        try {
            Boolean locked = redis.opsForValue()
                    .setIfAbsent(buildLockKey(bookId, userId), "1", LOCK_TTL);
            return Boolean.TRUE.equals(locked);
        } catch (Exception e) {
            log.warn("[SPEED-READ-CACHE] 获取生成锁失败，降级为无锁: {}", e.getMessage());
            return true;
        }
    }

    /**
     * 释放生成锁。
     */
    public void unlock(Long bookId, Long userId) {
        try {
            redis.delete(buildLockKey(bookId, userId));
        } catch (Exception ignored) {
        }
    }

    /**
     * 并发未抢到锁时，短暂等待先到者写入缓存。
     *
     * @return 先到者写入的缓存；超时仍无则返回 null
     */
    public String awaitCached(Long bookId, Long userId, String bookFp, String profileFp) {
        long deadline = System.currentTimeMillis() + LOCK_WAIT_MS;
        while (System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(LOCK_POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
            String cached = get(bookId, userId, bookFp, profileFp);
            if (cached != null) return cached;
        }
        return null;
    }

    /**
     * 流式回放缓存内容 — 保持与真实 LLM 输出一致的 SSE message 事件。
     * <p>
     * 分块时优先在换行处断开（前端按行解析"### 标题/内容行"），只有超长行才按
     * {@link #CHUNK_CHARS} 强制切分。每块间隔 12-60ms，整体控制在 ~2.5s。
     *
     * @return true 回放完成；false 连接已断开或线程被中断
     */
    public boolean replay(SseEmitter emitter, String cached) {
        if (cached == null || cached.isEmpty()) return false;

        log.info("[SPEED-READ-CACHE] 命中，流式回放 ({}字符)", cached.length());

        int chunks = (cached.length() + CHUNK_CHARS - 1) / CHUNK_CHARS;
        long delayMs = Math.max(12L, Math.min(60L, REPLAY_TARGET_MS / Math.max(1, chunks)));

        int index = 0;
        while (index < cached.length()) {
            int end = Math.min(index + CHUNK_CHARS, cached.length());
            int newline = cached.indexOf('\n', index);
            if (newline >= 0 && newline < end) {
                end = newline + 1;
            }
            String chunk = cached.substring(index, end);
            if (!SseHelper.safeSendEvent(emitter, "message", chunk)) {
                log.info("[SPEED-READ-CACHE] 连接已关闭，停止回放 (已回放 {}/{})", index, cached.length());
                return false;
            }
            index = end;
            if (index < cached.length() && !sleep(delayMs)) {
                return false;
            }
        }

        try {
            emitter.send(SseEmitter.event().name("done").data("[DONE]"));
            emitter.complete();
        } catch (Exception e) {
            log.warn("[SPEED-READ-CACHE] 发送 done 事件失败: {}", e.getMessage());
        }
        log.info("[SPEED-READ-CACHE] 回放完成");
        return true;
    }

    private boolean sleep(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static String encode(String bookFp, String profileFp, String content) {
        return bookFp + SEP + profileFp + SEP + content;
    }

    private static CacheEntry decode(String raw) {
        int first = raw.indexOf(SEP);
        if (first <= 0) return null;
        int second = raw.indexOf(SEP, first + 1);
        if (second < 0) return null;
        return new CacheEntry(
                raw.substring(0, first),
                raw.substring(first + 1, second),
                raw.substring(second + 1));
    }

    private String buildKey(Long userId) {
        return KEY_PREFIX + (userId != null ? userId : ANONYMOUS);
    }

    private String buildLockKey(Long bookId, Long userId) {
        return LOCK_PREFIX + (userId != null ? userId : ANONYMOUS) + ":" + bookId;
    }
}
