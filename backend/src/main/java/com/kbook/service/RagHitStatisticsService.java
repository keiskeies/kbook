package com.kbook.service;

import com.kbook.dto.BookProjection;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * RAG 向量命中统计服务
 * <p>
 * 记录每本书的 AI 问答中 Qdrant 向量检索的命中/未命中情况。
 * 风控策略：
 * 1. 未命中时检测零向量/空数据 → 立即重建（无需统计累积）
 * 2. 未命中率 ≥ 50% 且总查询 ≥ 10 → 统计触发重建
 * <p>
 * 前置校验（重建前必须满足）：
 * - contentEmbedded = true（扫描/上传跳过向量化的书不触发）
 * - 图书文件仍存在
 * <p>
 * Redis 存储结构：
 * <ul>
 *   <li>rag:hit:{bookId} — 命中次数</li>
 *   <li>rag:miss:{bookId} — 未命中次数</li>
 *   <li>rag:miss:ts:{bookId} — 首次未命中时间戳</li>
 *   <li>rag:auto_retry:{bookId} — 自动重试冷却标记（7 天）</li>
 *   <li>rag:zerovec_checked:{bookId} — 零向量已检测标记</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagHitStatisticsService {

    private final StringRedisTemplate redisTemplate;
    private final EmbeddingService embeddingService;
    private final com.kbook.service.BookParserService bookParserService;
    private final BookService bookService;

    private static final String HIT_KEY = "rag:hit:";
    private static final String MISS_KEY = "rag:miss:";
    private static final String MISS_TS_KEY = "rag:miss:ts:";
    private static final String AUTO_RETRY_KEY = "rag:auto_retry:";
    private static final String ZERO_VEC_CHECKED_KEY = "rag:zerovec_checked:";

    /** 总查询次数 ≥ 此值时才计算命中率，避免小样本误判 */
    private static final long MIN_TOTAL_QUERIES = 10;

    /** 未命中率超过此值触发自动重新向量化 */
    private static final double AUTO_RETRY_MISS_RATE = 0.5;

    /** 统计数据过期时间（30 天自动清除） */
    private static final Duration STATS_TTL = Duration.ofDays(30);

    /** 重建冷却期（7 天内不重复触发） */
    private static final Duration RETRY_COOLDOWN = Duration.ofDays(7);

    /**
     * 防止并发重复重建的标记（内存级）
     */
    private final Set<Long> rebuildingBooks = ConcurrentHashMap.newKeySet();

    /**
     * 记录一次向量检索命中
     * 命中时清除该书的未命中统计（表示数据已恢复正常）
     */
    public void recordHit(Long bookId) {
        String key = HIT_KEY + bookId;
        redisTemplate.opsForValue().increment(key);
        redisTemplate.expire(key, STATS_TTL);

        // 命中说明向量数据正常，清除未命中相关统计
        redisTemplate.delete(List.of(
                MISS_KEY + bookId,
                MISS_TS_KEY + bookId,
                ZERO_VEC_CHECKED_KEY + bookId
        ));
    }

    /**
     * 记录一次向量检索未命中
     * <p>
     * 风控流程：
     * 1. 零向量/空数据检测（仅一次）→ 立即重建
     * 2. 更新统计计数 → 检查未命中率 → 触发重建
     */
    public void recordMiss(Long bookId) {
        // 1. 零向量/空数据检测（仅一次）→ 立即重建
        if (checkZeroVectorsAndRebuild(bookId)) {
            return;
        }

        // 2. 未命中计数
        String missKey = MISS_KEY + bookId;
        long missCount = redisTemplate.opsForValue().increment(missKey);
        redisTemplate.expire(missKey, STATS_TTL);

        // 首次未命中时记录时间
        String tsKey = MISS_TS_KEY + bookId;
        if (missCount == 1) {
            redisTemplate.opsForValue().set(tsKey, String.valueOf(Instant.now().getEpochSecond()));
            redisTemplate.expire(tsKey, STATS_TTL);
        }

        // 3. 检查统计阈值触发重建
        checkAndTriggerAutoRetry(bookId, missCount);
    }

    /**
     * 检测零向量/空数据，如果存在则立即重建
     * 优化：检测通过（向量正常）仅缓存 1 小时，避免 30 天内无法重试；
     *      检测异常（空/零向量）触发重建后缓存 30 天。
     * @return true = 已触发重建
     */
    private boolean checkZeroVectorsAndRebuild(Long bookId) {
        String checkedKey = ZERO_VEC_CHECKED_KEY + bookId;
        Boolean alreadyChecked = redisTemplate.hasKey(checkedKey);
        if (Boolean.TRUE.equals(alreadyChecked)) {
            return false; // 短期内已检测过，跳过
        }

        boolean hasIssue = embeddingService.detectZeroContentVectors(bookId);
        if (hasIssue) {
            // 确认为空或零向量，触发重建并长期标记
            redisTemplate.opsForValue().set(checkedKey, "1", STATS_TTL);
            triggerImmediateRebuild(bookId, "零向量/空数据检测");
            return true;
        } else {
            // 向量正常，短期缓存检测结果，避免频繁调用 Qdrant REST API
            redisTemplate.opsForValue().set(checkedKey, "1", Duration.ofHours(1));
            return false;
        }
    }

    /**
     * 获取某本书的命中统计
     */
    public Map<String, Object> getStatistics(Long bookId) {
        long hits = getCounter(HIT_KEY + bookId);
        long misses = getCounter(MISS_KEY + bookId);
        long total = hits + misses;
        double hitRate = total > 0 ? (double) hits / total : 0.0;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("bookId", bookId);
        result.put("hits", hits);
        result.put("misses", misses);
        result.put("totalQueries", total);
        result.put("hitRate", Math.round(hitRate * 10000.0) / 100.0);
        return result;
    }

    /**
     * 获取最近未命中率最高的书籍列表
     */
    public List<Map<String, Object>> getLowHitBooks(int topN) {
        Set<String> missKeys = redisTemplate.keys(MISS_KEY + "*");
        if (missKeys.isEmpty()) {
            return Collections.emptyList();
        }

        List<Map<String, Object>> books = new ArrayList<>();
        for (String missKey : missKeys) {
            String bookIdStr = missKey.substring(MISS_KEY.length());
            long bookId;
            try {
                bookId = Long.parseLong(bookIdStr);
            } catch (NumberFormatException e) {
                continue;
            }

            long hits = getCounter(HIT_KEY + bookIdStr);
            long misses = getCounter(missKey);
            long total = hits + misses;

            if (total < MIN_TOTAL_QUERIES) continue;

            double missRate = (double) misses / total;
            if (missRate < 0.1) continue;

            Map<String, Object> stat = new LinkedHashMap<>();
            stat.put("bookId", bookId);
            stat.put("hits", hits);
            stat.put("misses", misses);
            stat.put("totalQueries", total);
            stat.put("missRate", Math.round(missRate * 10000.0) / 100.0);

            String tsStr = redisTemplate.opsForValue().get(MISS_TS_KEY + bookIdStr);
            if (tsStr != null) {
                try {
                    long ts = Long.parseLong(tsStr);
                    stat.put("firstMissAt", DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                            .withZone(ZoneId.systemDefault())
                            .format(Instant.ofEpochSecond(ts)));
                } catch (NumberFormatException ignored) {}
            }

            books.add(stat);
        }

        books.sort((a, b) -> Double.compare(
                (Double) b.get("missRate"),
                (Double) a.get("missRate")
        ));

        return books.stream().limit(topN).collect(Collectors.toList());
    }

    /**
     * 清除某本书的命中统计
     */
    public void clearStatistics(Long bookId) {
        redisTemplate.delete(List.of(
                HIT_KEY + bookId,
                MISS_KEY + bookId,
                MISS_TS_KEY + bookId,
                AUTO_RETRY_KEY + bookId,
                ZERO_VEC_CHECKED_KEY + bookId
        ));
        rebuildingBooks.remove(bookId);
    }

    /**
     * 统计阈值触发自动重建
     * 增加快速通道：连续未命中 3 次直接触发，无需等待 10 次阈值
     */
    private void checkAndTriggerAutoRetry(Long bookId, long missCount) {
        // 快速通道：连续未命中达到 3 次，直接触发重建
        if (missCount >= 3) {
            triggerImmediateRebuild(bookId, String.format("连续未命中 %d 次", missCount));
            return;
        }

        long hits = getCounter(HIT_KEY + bookId);
        long total = hits + missCount;

        if (total < MIN_TOTAL_QUERIES) return;

        double missRate = (double) missCount / total;
        if (missRate < AUTO_RETRY_MISS_RATE) return;

        triggerImmediateRebuild(bookId, String.format("未命中率 %.1f%%", missRate * 100));
    }

    /**
     * 触发立即重建（统一入口，防并发）
     * 前置校验：contentEmbedded 标志 + 文件存在性
     */
    private void triggerImmediateRebuild(Long bookId, String reason) {
        // 内存级防并发
        if (!rebuildingBooks.add(bookId)) return;

        try {
            // Redis 冷却期检查（SET NX 原子操作）
            String retryKey = AUTO_RETRY_KEY + bookId;
            Boolean canRetry = redisTemplate.opsForValue().setIfAbsent(retryKey, String.valueOf(System.currentTimeMillis()), RETRY_COOLDOWN);
            if (!Boolean.TRUE.equals(canRetry)) {
                log.debug("风控重建跳过（冷却期内）: bookId={}, reason={}", bookId, reason);
                return;
            }

            // 前置校验 1：图书是否存在
            BookProjection book;
            try {
                book = bookService.getBookProjectionById(bookId);
            } catch (Exception e) {
                log.warn("风控重建跳过（图书不存在）: bookId={}", bookId);
                return;
            }

            // 注意：不再检查 contentEmbedded 标志。
            // 即使扫描/上传时跳过了向量化，只要用户发起对话且 RAG 未命中，
            // 就视为强烈的按需向量化信号，应触发重建。

            // 前置校验 2：图书文件是否存在
            if (book.getFileUrl() == null || !Files.exists(Paths.get(book.getFileUrl()))) {
                log.warn("风控重建跳过（图书文件不存在）: bookId={}, fileUrl={}", bookId, book.getFileUrl());
                return;
            }

            log.warn("========== 自动风控触发（{}）：bookId={}, title={} ==========", reason, bookId, book.getTitle());

            int chunkCount = bookParserService.generateContentEmbeddingWithCount(bookId);
            if (chunkCount > 0) {
                log.info("自动风控：bookId={} 全文重新向量化成功，共 {} chunks", bookId, chunkCount);
                clearStatistics(bookId);
            } else {
                log.warn("自动风控：bookId={} 重新向量化返回 0 chunks，保留统计以便后续重试", bookId);
                // 不清除统计，允许下次继续触发
                redisTemplate.delete(retryKey);
            }
        } catch (Exception e) {
            log.error("自动风控：bookId={} 全文重新向量化失败: {}", bookId, e.getMessage());
            // 失败时清除冷却标记，允许下次重试
            redisTemplate.delete(AUTO_RETRY_KEY + bookId);
        } finally {
            rebuildingBooks.remove(bookId);
        }
    }

    private long getCounter(String key) {
        String val = redisTemplate.opsForValue().get(key);
        if (val == null) return 0;
        try {
            return Long.parseLong(val);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
