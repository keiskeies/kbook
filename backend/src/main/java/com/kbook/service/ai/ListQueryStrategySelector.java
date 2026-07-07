package com.kbook.service.ai;

import com.kbook.entity.Book;
import com.kbook.service.embedding.EmbeddingService;
import com.kbook.service.embedding.TocQualityEvaluator;
import com.kbook.service.embedding.TocQualityEvaluator.TocQualityLevel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 列表型问题 RAG 策略选择器 — 根据 toc 质量 + 实际 chunks 数选择检索档位。
 * <p>
 * 三档策略：
 * <ul>
 *   <li>档 1（FULL_SCAN）：toc 完整 或 小书 → 全量 scroll + LLM 精筛</li>
 *   <li>档 2（TOC_RANGE）：toc 中等/完整 + 中大书 → toc 范围补拉</li>
 *   <li>档 3（CLUSTER_EXPAND）：toc 不完整 + 中大书 → 簇扩展 + LLM 精筛</li>
 * </ul>
 * <p>
 * 决策矩阵（toc 质量 \ chunks 数）：
 * <pre>
 *              小(<200)   中(200-2000)   大(>2000)
 * 完整(≥0.7)    档1          档2           档2
 * 中等(0.3-0.7) 档1          档2           档2
 * 不完整(<0.3)  档1          档3           档3
 * </pre>
 * <p>
 * 设计原理：
 * - 小书一律走档 1（最简单可靠，全量 scroll 开销可接受）
 * - toc 完整/中等 → 档 2（toc 已经过滤，无需 LLM 精筛，最经济）
 * - toc 不完整 + 中大书 → 档 3（toc 不可用，只能靠簇扩展）
 */
@Slf4j
@Service
public class ListQueryStrategySelector {

    /** 小书 chunks 数阈值 */
    public static final long SMALL_BOOK_THRESHOLD = 200;
    /** 大书 chunks 数阈值 */
    public static final long LARGE_BOOK_THRESHOLD = 2000;

    private final TocQualityEvaluator tocQualityEvaluator;
    private final EmbeddingService embeddingService;

    public ListQueryStrategySelector(TocQualityEvaluator tocQualityEvaluator,
                                     EmbeddingService embeddingService) {
        this.tocQualityEvaluator = tocQualityEvaluator;
        this.embeddingService = embeddingService;
    }

    /**
     * RAG 检索策略档位。
     */
    public enum Strategy {
        /** 档 1：全量 scroll + LLM 精筛 */
        FULL_SCAN,
        /** 档 2：toc 范围补拉 */
        TOC_RANGE,
        /** 档 3：簇扩展 + LLM 精筛 */
        CLUSTER_EXPAND
    }

    /**
     * 根据书籍 toc 质量和实际 chunks 数选择策略。
     *
     * @param book 书籍实体
     * @return 策略档位；查询失败时默认走档 3（最通用的兜底）
     */
    public Strategy selectStrategy(Book book) {
        if (book == null || book.getId() == null) {
            return Strategy.CLUSTER_EXPAND;
        }

        // 1. 评估 toc 质量（命中缓存直接返回）
        float tocScore = tocQualityEvaluator.evaluateAndCache(book);
        TocQualityLevel tocLevel = TocQualityEvaluator.levelOf(tocScore);

        // 2. 查询实际 chunks 数
        long chunkCount = embeddingService.countContentChunks(book.getId());

        // 3. 决策
        Strategy strategy = decide(tocLevel, chunkCount);

        log.info("[ListQueryStrategy] bookId={}, tocScore={}, tocLevel={}, chunks={} → 策略={}",
                book.getId(), String.format("%.2f", tocScore), tocLevel, chunkCount, strategy);

        return strategy;
    }

    /**
     * 决策矩阵实现（包可见，便于测试）。
     */
    static Strategy decide(TocQualityLevel tocLevel, long chunkCount) {
        // 小书一律走档 1（最简单可靠）
        if (chunkCount < SMALL_BOOK_THRESHOLD) {
            return Strategy.FULL_SCAN;
        }

        // 中大书：按 toc 质量分流
        if (tocLevel == TocQualityLevel.INCOMPLETE) {
            // toc 不完整 → 档 3（簇扩展）
            return Strategy.CLUSTER_EXPAND;
        }
        // toc 完整或中等 → 档 2（toc 范围补拉）
        return Strategy.TOC_RANGE;
    }
}
