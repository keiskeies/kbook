package com.kbook.service.embedding.event;

/**
 * RAG 检索命中事件 — 由 EmbeddingService 在 searchContent 完成后发布
 * <p>
 * 用于解耦 EmbeddingService 与 RagHitStatisticsService 的循环依赖：
 * EmbeddingService 不再直接调用 RagHitStatisticsService.recordHit/recordMiss，
 * 改为发布本事件，由 RagHitStatisticsService 监听处理。
 * <p>
 * 事件类型：
 * - HIT: 检索有结果（topScore 为最高分）
 * - MISS: 检索无结果或异常（topScore 忽略）
 */
public record RagHitEvent(Long bookId, Type type, double topScore) {

    public enum Type {
        HIT,
        MISS
    }

    public static RagHitEvent hit(Long bookId, double topScore) {
        return new RagHitEvent(bookId, Type.HIT, topScore);
    }

    public static RagHitEvent miss(Long bookId) {
        return new RagHitEvent(bookId, Type.MISS, 0.0);
    }
}
