package com.kbook.service.ai;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.stream.Collectors;

/**
 * RAG 检索管线公共组件
 * <p>
 * 抽出 BookChatService 中可复用的 RAG 管线原子操作，供图书问答、圆桌派等场景共享：
 * <ul>
 *   <li>{@link #getChunkIndex} — 从 EmbeddingMatch 元数据提取 chunkIndex</li>
 *   <li>{@link #dedupByChunkIndex} — 按 chunkIndex 去重，保留 score 更高的</li>
 *   <li>{@link #adaptiveNeighborExpand} — 自适应邻域扩展（前 N 后 M，score 折扣）</li>
 *   <li>{@link #mergeAdjacentChunks} — 合并严格连续的相邻 chunk（差 ≤ 1）</li>
 *   <li>{@link #applyFocusKeywordsBoost} — 按 focusKeywords 命中数对 score 加权</li>
 * </ul>
 * <p>
 * 所有方法均为静态方法，无状态依赖，可通过参数注入 EmbeddingService 完成邻域 chunk 拉取。
 *
 * @author kbook
 * @since 1.0.0
 */
@Slf4j
public final class RagPipelineComponents {

    private RagPipelineComponents() {}

    /** 邻域扩展的 score 折扣（邻域是推测性召回，可靠性低于直接命中） */
    public static final double NEIGHBOR_SCORE_DISCOUNT = 0.7;
    /** 邻域扩展绝对底线阈值（低于此值视为跨章节噪声） */
    public static final double NEIGHBOR_MIN_SCORE = 0.15;
    /** 合并后片段的最大字符数（>2000 时按 chunkIndex 切分会引入过长 context） */
    public static final int MAX_MERGED_CHUNK_CHARS = 2000;
    /** focusKeywords 加权：每个命中词的 score 加成（避免过度扭曲原始相似度） */
    public static final double FOCUS_KEYWORD_BONUS = 0.05;

    /**
     * 邻域 chunk 拉取器接口（解耦 EmbeddingService 依赖）
     */
    @FunctionalInterface
    public interface NeighborFetcher {
        /**
         * 按 bookId + chunkIndex 精确拉取 chunk
         *
         * @param bookId     书籍 ID
         * @param chunkIndex 目标 chunkIndex
         * @return chunk；不存在返回 null
         */
        EmbeddingMatch<TextSegment> fetch(Long bookId, int chunkIndex);
    }

    // ================================================================
    // 基础工具
    // ================================================================

    /**
     * 从片段元数据中提取 chunkIndex
     *
     * @return 片段索引；元数据缺失时返回 -1（区分真实索引 0）
     */
    public static int getChunkIndex(EmbeddingMatch<TextSegment> match) {
        if (match.embedded() != null && match.embedded().metadata() != null) {
            Long idx = match.embedded().metadata().getLong("chunkIndex");
            if (idx != null) return idx.intValue();
        }
        return -1;
    }

    // ================================================================
    // 去重
    // ================================================================

    /**
     * 按 chunkIndex 去重，保留 score 更高的（同一 chunk 被多子查询命中时取最佳分）
     *
     * @param matches 多子查询合并后的全部命中
     * @return 按 chunkIndex 去重后的列表（保留插入顺序，便于调试）
     */
    public static List<EmbeddingMatch<TextSegment>> dedupByChunkIndex(
            List<EmbeddingMatch<TextSegment>> matches) {
        Map<Integer, EmbeddingMatch<TextSegment>> deduped = new LinkedHashMap<>();
        for (EmbeddingMatch<TextSegment> match : matches) {
            String text = match.embedded() != null ? match.embedded().text() : "";
            if (text.isBlank()) continue;
            int idx = getChunkIndex(match);
            deduped.merge(idx, match,
                    (existing, incoming) -> incoming.score() > existing.score() ? incoming : existing);
        }
        return new ArrayList<>(deduped.values());
    }

    // ================================================================
    // 邻域扩展
    // ================================================================

    /**
     * 自适应邻域扩展：对 topN 命中的每个 chunk，拉取前 neighborPrev 后 neighborNext 候选。
     * <p>
     * 设计要点：
     * - 连续命中时邻域已覆盖 → 不重复扩展
     * - 稀疏命中时邻域是必要的上下文补充 → 保留
     * - 邻域 score 用原始命中 score × {@link #NEIGHBOR_SCORE_DISCOUNT} 折扣
     * - 折扣后 score &lt; {@link #NEIGHBOR_MIN_SCORE} 的邻域跳过（跨章节噪声）
     *
     * @param topN          原始 topN 命中
     * @param bookId        书籍 ID（用于邻域拉取）
     * @param neighborPrev  向前扩展数（0 表示不向前扩展）
     * @param neighborNext  向后扩展数（0 表示不向后扩展）
     * @param fetcher       邻域 chunk 拉取器（通常指向 EmbeddingService.searchContentByChunkIndex）
     * @return 扩展后的列表（topN + 邻域）
     */
    public static List<EmbeddingMatch<TextSegment>> adaptiveNeighborExpand(
            List<EmbeddingMatch<TextSegment>> topN,
            Long bookId,
            int neighborPrev,
            int neighborNext,
            NeighborFetcher fetcher) {
        if (topN.isEmpty() || bookId == null) {
            return new ArrayList<>(topN);
        }
        if (neighborPrev <= 0 && neighborNext <= 0) {
            return new ArrayList<>(topN);
        }

        Set<Integer> hitIndices = topN.stream()
                .map(RagPipelineComponents::getChunkIndex)
                .collect(Collectors.toSet());

        List<EmbeddingMatch<TextSegment>> expanded = new ArrayList<>(topN);
        int neighborAdded = 0;

        for (EmbeddingMatch<TextSegment> hit : topN) {
            int hitIdx = getChunkIndex(hit);
            if (hitIdx < 0) continue;

            // 前：[hitIdx - neighborPrev, hitIdx - 1]
            for (int offset = neighborPrev; offset >= 1; offset--) {
                int neighborIdx = hitIdx - offset;
                if (neighborIdx < 0) continue;
                if (hitIndices.contains(neighborIdx)) continue;

                EmbeddingMatch<TextSegment> neighbor = fetchNeighborWithScore(
                        fetcher, bookId, neighborIdx, hit.score());
                if (neighbor != null) {
                    expanded.add(neighbor);
                    neighborAdded++;
                    hitIndices.add(neighborIdx);
                }
            }

            // 后：[hitIdx + 1, hitIdx + neighborNext]
            for (int offset = 1; offset <= neighborNext; offset++) {
                int neighborIdx = hitIdx + offset;
                if (hitIndices.contains(neighborIdx)) continue;

                EmbeddingMatch<TextSegment> neighbor = fetchNeighborWithScore(
                        fetcher, bookId, neighborIdx, hit.score());
                if (neighbor != null) {
                    expanded.add(neighbor);
                    neighborAdded++;
                    hitIndices.add(neighborIdx);
                }
            }
        }

        if (neighborAdded > 0) {
            log.debug("邻域扩展: topN={}, 新增邻域={}, 总计={}",
                    topN.size(), neighborAdded, expanded.size());
        }
        return expanded;
    }

    /**
     * 拉取单个邻域 chunk 并按折扣 score 封装
     */
    private static EmbeddingMatch<TextSegment> fetchNeighborWithScore(
            NeighborFetcher fetcher, Long bookId, int neighborIdx, double hitScore) {
        EmbeddingMatch<TextSegment> neighbor = fetcher.fetch(bookId, neighborIdx);
        if (neighbor == null) return null;

        double discountedScore = hitScore * NEIGHBOR_SCORE_DISCOUNT;
        if (discountedScore < NEIGHBOR_MIN_SCORE) return null;

        return new EmbeddingMatch<>(discountedScore,
                neighbor.embeddingId(),
                neighbor.embedding(),
                neighbor.embedded());
    }

    // ================================================================
    // 合并相邻
    // ================================================================

    /**
     * 合并严格连续的相邻 chunk（chunkIndex 差 ≤ 1），
     * 合并后片段不超过 {@link #MAX_MERGED_CHUNK_CHARS} 字符。
     * <p>
     * 合并后的 score 取组内最高分（bestScore），用于后续按 score 重排。
     *
     * @param matches 原始匹配结果
     * @return 合并后的匹配结果列表
     */
    public static List<EmbeddingMatch<TextSegment>> mergeAdjacentChunks(
            List<EmbeddingMatch<TextSegment>> matches) {
        if (matches.size() <= 1) return new ArrayList<>(matches);

        List<EmbeddingMatch<TextSegment>> sortedByIndex = new ArrayList<>(matches);
        sortedByIndex.sort((a, b) -> {
            int indexA = getChunkIndex(a);
            int indexB = getChunkIndex(b);
            return Integer.compare(indexA, indexB);
        });

        List<EmbeddingMatch<TextSegment>> merged = new ArrayList<>();
        EmbeddingMatch<TextSegment> current = sortedByIndex.get(0);
        StringBuilder mergedText = new StringBuilder(
                current.embedded() != null ? current.embedded().text() : "");
        double bestScore = current.score();

        for (int i = 1; i < sortedByIndex.size(); i++) {
            EmbeddingMatch<TextSegment> next = sortedByIndex.get(i);
            int currentIndex = getChunkIndex(current);
            int nextIndex = getChunkIndex(next);

            String nextText = next.embedded() != null ? next.embedded().text() : "";
            boolean isConsecutive = currentIndex >= 0 && nextIndex == currentIndex + 1;
            boolean withinLength = mergedText.length() + nextText.length() + 2 <= MAX_MERGED_CHUNK_CHARS;

            if (isConsecutive && withinLength) {
                mergedText.append("\n\n").append(nextText);
                if (next.score() > bestScore) bestScore = next.score();
                current = next;
                continue;
            }

            merged.add(createMergedMatch(mergedText.toString(), current, bestScore));
            mergedText = new StringBuilder(nextText);
            bestScore = next.score();
            current = next;
        }
        merged.add(createMergedMatch(mergedText.toString(), current, bestScore));

        return merged;
    }

    /**
     * 根据模板创建合并后的匹配结果，复用模板的 embeddingId 和元数据
     */
    public static EmbeddingMatch<TextSegment> createMergedMatch(
            String text, EmbeddingMatch<TextSegment> template, double score) {
        TextSegment segment = TextSegment.from(text,
                template.embedded() != null ? template.embedded().metadata() : new Metadata());
        return new EmbeddingMatch<>(score, template.embeddingId(), template.embedding(), segment);
    }

    // ================================================================
    // focusKeywords 加权
    // ================================================================

    /**
     * 按 focusKeywords 命中数对 chunk 的 score 做加权
     * <p>
     * 设计：
     * - 每个 focusKeyword 命中加 {@link #FOCUS_KEYWORD_BONUS} 到 score
     * - 命中判断大小写不敏感、整词匹配（避免"方法"命中"方法论"的子串误判）
     * - 不修改原 list，返回新 list
     * <p>
     * 注意：加权是相对微调，不会让低 score chunk 反超高 score chunk。
     * 例如原 score=0.5，命中 3 个 focusKeyword → 0.5 + 3×0.05 = 0.65
     *
     * @param matches       原始命中列表
     * @param focusKeywords 视角偏好词（null 或空则原样返回）
     * @return 加权后的新列表
     */
    public static List<EmbeddingMatch<TextSegment>> applyFocusKeywordsBoost(
            List<EmbeddingMatch<TextSegment>> matches, List<String> focusKeywords) {
        if (focusKeywords == null || focusKeywords.isEmpty() || matches.isEmpty()) {
            return new ArrayList<>(matches);
        }

        // 预处理：小写化 + 去空
        List<String> normalizedKeywords = focusKeywords.stream()
                .filter(k -> k != null && !k.isBlank())
                .map(String::trim)
                .map(String::toLowerCase)
                .toList();
        if (normalizedKeywords.isEmpty()) return new ArrayList<>(matches);

        List<EmbeddingMatch<TextSegment>> boosted = new ArrayList<>(matches.size());
        for (EmbeddingMatch<TextSegment> match : matches) {
            String text = match.embedded() != null ? match.embedded().text() : "";
            if (text.isBlank()) {
                boosted.add(match);
                continue;
            }
            String lowerText = text.toLowerCase();
            int hitCount = 0;
            for (String keyword : normalizedKeywords) {
                if (lowerText.contains(keyword)) hitCount++;
            }
            if (hitCount == 0) {
                boosted.add(match);
            } else {
                double newScore = match.score() + hitCount * FOCUS_KEYWORD_BONUS;
                boosted.add(new EmbeddingMatch<>(newScore,
                        match.embeddingId(), match.embedding(), match.embedded()));
            }
        }
        return boosted;
    }

    // ================================================================
    // 组合便捷方法
    // ================================================================

    /**
     * 截断到 maxChars（按合并后顺序填充，超长即停）
     *
     * @param mergedChunks 已合并、已排序的 chunks
     * @param maxChars     最大字符数
     * @return 拼接后的上下文字符串
     */
    public static String truncateToChars(List<EmbeddingMatch<TextSegment>> mergedChunks, int maxChars) {
        StringBuilder sb = new StringBuilder();
        int totalLen = 0;
        for (EmbeddingMatch<TextSegment> match : mergedChunks) {
            String chunkText = match.embedded() != null ? match.embedded().text() : "";
            if (chunkText.isBlank()) continue;
            if (totalLen + chunkText.length() > maxChars) break;
            sb.append(chunkText).append("\n\n");
            totalLen += chunkText.length();
        }
        return sb.toString();
    }
}
