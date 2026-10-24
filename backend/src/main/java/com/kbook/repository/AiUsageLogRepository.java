package com.kbook.repository;

import com.kbook.common.repository.BaseRepository;
import com.kbook.entity.AiUsageLog;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * AI 调用用量数据访问层
 */
public interface AiUsageLogRepository extends BaseRepository<AiUsageLog, Long> {

    // ==================== 汇总 ====================

    @Query("SELECT COALESCE(SUM(u.totalTokens), 0), COALESCE(SUM(u.cost), 0) FROM AiUsageLog u")
    List<Object[]> sumTotals();

    @Query("SELECT COALESCE(SUM(u.totalTokens), 0), COALESCE(SUM(u.cost), 0) FROM AiUsageLog u " +
            "WHERE u.createdAt >= :since")
    List<Object[]> sumTotalsSince(@Param("since") LocalDateTime since);

    @Query("SELECT COALESCE(SUM(u.totalTokens), 0) FROM AiUsageLog u " +
            "WHERE u.inputPrice IS NULL OR u.outputPrice IS NULL")
    long sumUnpricedTokens();

    @Query("SELECT COALESCE(SUM(u.totalTokens), 0) FROM AiUsageLog u " +
            "WHERE (u.inputPrice IS NULL OR u.outputPrice IS NULL) AND u.createdAt >= :since")
    long sumUnpricedTokensSince(@Param("since") LocalDateTime since);

    // ==================== 分组 ====================

    @Query("SELECT u.scene, COALESCE(SUM(u.totalTokens), 0), COALESCE(SUM(u.cost), 0), COUNT(u) " +
            "FROM AiUsageLog u GROUP BY u.scene ORDER BY SUM(u.totalTokens) DESC")
    List<Object[]> groupByScene();

    @Query("SELECT u.scene, COALESCE(SUM(u.totalTokens), 0), COALESCE(SUM(u.cost), 0), COUNT(u) " +
            "FROM AiUsageLog u WHERE u.createdAt >= :since GROUP BY u.scene ORDER BY SUM(u.totalTokens) DESC")
    List<Object[]> groupBySceneSince(@Param("since") LocalDateTime since);

    @Query("SELECT u.modelName, COALESCE(SUM(u.totalTokens), 0), COALESCE(SUM(u.cost), 0), COUNT(u) " +
            "FROM AiUsageLog u GROUP BY u.modelName ORDER BY SUM(u.totalTokens) DESC")
    List<Object[]> groupByModel();

    @Query("SELECT u.modelName, COALESCE(SUM(u.totalTokens), 0), COALESCE(SUM(u.cost), 0), COUNT(u) " +
            "FROM AiUsageLog u WHERE u.createdAt >= :since GROUP BY u.modelName ORDER BY SUM(u.totalTokens) DESC")
    List<Object[]> groupByModelSince(@Param("since") LocalDateTime since);

    @Query("SELECT u.userId, COALESCE(SUM(u.totalTokens), 0), COALESCE(SUM(u.cost), 0), COUNT(u) " +
            "FROM AiUsageLog u WHERE u.userId IS NOT NULL GROUP BY u.userId ORDER BY SUM(u.totalTokens) DESC")
    List<Object[]> groupByUser();

    @Query("SELECT u.userId, COALESCE(SUM(u.totalTokens), 0), COALESCE(SUM(u.cost), 0), COUNT(u) " +
            "FROM AiUsageLog u WHERE u.userId IS NOT NULL AND u.createdAt >= :since " +
            "GROUP BY u.userId ORDER BY SUM(u.totalTokens) DESC")
    List<Object[]> groupByUserSince(@Param("since") LocalDateTime since);

    @Query("SELECT u.bookId, COALESCE(SUM(u.totalTokens), 0), COALESCE(SUM(u.cost), 0), COUNT(u) " +
            "FROM AiUsageLog u WHERE u.bookId IS NOT NULL GROUP BY u.bookId ORDER BY SUM(u.totalTokens) DESC")
    List<Object[]> groupByBook();

    @Query("SELECT u.bookId, COALESCE(SUM(u.totalTokens), 0), COALESCE(SUM(u.cost), 0), COUNT(u) " +
            "FROM AiUsageLog u WHERE u.bookId IS NOT NULL AND u.createdAt >= :since " +
            "GROUP BY u.bookId ORDER BY SUM(u.totalTokens) DESC")
    List<Object[]> groupByBookSince(@Param("since") LocalDateTime since);

    // ==================== 质量指标 ====================

    @Query("SELECT COUNT(u), SUM(CASE WHEN u.success = true THEN 1 ELSE 0 END), AVG(u.latencyMs), " +
            "SUM(CASE WHEN u.finishReason = 'LENGTH' THEN 1 ELSE 0 END), COUNT(u.latencyMs) FROM AiUsageLog u")
    List<Object[]> qualityTotals();

    @Query("SELECT COUNT(u), SUM(CASE WHEN u.success = true THEN 1 ELSE 0 END), AVG(u.latencyMs), " +
            "SUM(CASE WHEN u.finishReason = 'LENGTH' THEN 1 ELSE 0 END), COUNT(u.latencyMs) FROM AiUsageLog u " +
            "WHERE u.createdAt >= :since")
    List<Object[]> qualitySince(@Param("since") LocalDateTime since);

    @Query(value = "SELECT latency_ms FROM ai_usage_log " +
            "WHERE latency_ms IS NOT NULL AND created_at >= :since " +
            "ORDER BY latency_ms LIMIT 1 OFFSET :offset", nativeQuery = true)
    Integer p95LatencySince(@Param("since") LocalDateTime since, @Param("offset") long offset);

    @Query(value = "SELECT latency_ms FROM ai_usage_log " +
            "WHERE latency_ms IS NOT NULL ORDER BY latency_ms LIMIT 1 OFFSET :offset", nativeQuery = true)
    Integer p95Latency(@Param("offset") long offset);

    // ==================== 保留策略 ====================

    @Modifying
    @Transactional
    @Query("DELETE FROM AiUsageLog u WHERE u.createdAt < :cutoff")
    int deleteBefore(@Param("cutoff") LocalDateTime cutoff);

    @Query("SELECT COALESCE(SUM(u.cost), 0) FROM AiUsageLog u WHERE u.createdAt >= :since")
    Double sumCostSince(@Param("since") LocalDateTime since);

    // ==================== 自定义区间 ====================

    @Query("SELECT COALESCE(SUM(u.totalTokens), 0), COALESCE(SUM(u.cost), 0) FROM AiUsageLog u " +
            "WHERE u.createdAt >= :start AND u.createdAt < :end")
    List<Object[]> sumTotalsBetween(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    @Query("SELECT COALESCE(SUM(u.totalTokens), 0) FROM AiUsageLog u " +
            "WHERE (u.inputPrice IS NULL OR u.outputPrice IS NULL) " +
            "AND u.createdAt >= :start AND u.createdAt < :end")
    long sumUnpricedTokensBetween(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    @Query("SELECT u.scene, COALESCE(SUM(u.totalTokens), 0), COALESCE(SUM(u.cost), 0), COUNT(u) " +
            "FROM AiUsageLog u WHERE u.createdAt >= :start AND u.createdAt < :end " +
            "GROUP BY u.scene ORDER BY SUM(u.totalTokens) DESC")
    List<Object[]> groupBySceneBetween(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    @Query("SELECT u.modelName, COALESCE(SUM(u.totalTokens), 0), COALESCE(SUM(u.cost), 0), COUNT(u) " +
            "FROM AiUsageLog u WHERE u.createdAt >= :start AND u.createdAt < :end " +
            "GROUP BY u.modelName ORDER BY SUM(u.totalTokens) DESC")
    List<Object[]> groupByModelBetween(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    @Query("SELECT u.userId, COALESCE(SUM(u.totalTokens), 0), COALESCE(SUM(u.cost), 0), COUNT(u) " +
            "FROM AiUsageLog u WHERE u.userId IS NOT NULL " +
            "AND u.createdAt >= :start AND u.createdAt < :end " +
            "GROUP BY u.userId ORDER BY SUM(u.totalTokens) DESC")
    List<Object[]> groupByUserBetween(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    @Query("SELECT u.bookId, COALESCE(SUM(u.totalTokens), 0), COALESCE(SUM(u.cost), 0), COUNT(u) " +
            "FROM AiUsageLog u WHERE u.bookId IS NOT NULL " +
            "AND u.createdAt >= :start AND u.createdAt < :end " +
            "GROUP BY u.bookId ORDER BY SUM(u.totalTokens) DESC")
    List<Object[]> groupByBookBetween(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    @Query("SELECT COUNT(u), SUM(CASE WHEN u.success = true THEN 1 ELSE 0 END), AVG(u.latencyMs), " +
            "SUM(CASE WHEN u.finishReason = 'LENGTH' THEN 1 ELSE 0 END), COUNT(u.latencyMs) " +
            "FROM AiUsageLog u WHERE u.createdAt >= :start AND u.createdAt < :end")
    List<Object[]> qualityBetween(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    @Query(value = "SELECT latency_ms FROM ai_usage_log " +
            "WHERE latency_ms IS NOT NULL AND created_at >= :start AND created_at < :end " +
            "ORDER BY latency_ms LIMIT 1 OFFSET :offset", nativeQuery = true)
    Integer p95LatencyBetween(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end,
                              @Param("offset") long offset);
}
