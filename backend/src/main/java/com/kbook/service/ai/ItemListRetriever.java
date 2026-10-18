package com.kbook.service.ai;

import com.kbook.entity.Book;
import com.kbook.service.ai.outline.AnswerOutline;
import com.kbook.service.embedding.EmbeddingService;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * 列表展开流水线的逐条定向检索器（§4.6）。
 * <p>
 * 与 {@link ListQueryRetriever}（三档全局策略，保留给非流水线回退路径）并列，
 * 本类为 outline 的<b>每个条目独立</b>检索材料并配独立额度：
 * <ol>
 *   <li>锚点优先：{@code item.chunkAnchors} 非空 → 按位置范围 scroll（min-1 ~ max+2，绕过相似度）；</li>
 *   <li>锚点缺失/无结果 → {@code item.keywords} 向量检索（topN=4），
 *       再走 {@link RagPipelineComponents#adaptiveNeighborExpand}（前1后2）+ 合并相邻；</li>
 *   <li>该条独立配额 ≤ {@link #PER_ITEM_MAX_CHARS} 字符 —— <b>逐条配额是治 H4 的关键</b>：
 *       全局 score 排序会让材料集中落在书的一处，后几项无原文可依；
 *       逐条配额让"每条都有材料"从概率变成代码不变量。</li>
 * </ol>
 * 检索是纯向量查询（无 LLM 调用），N 条并行走 prefetchExecutor，成本 ≈ 一次检索往返。
 *
 * @author kbook
 * @since 1.1.0
 */
@Slf4j
@Component
public class ItemListRetriever {

    /** 每条目独立材料配额（字符）。 */
    public static final int PER_ITEM_MAX_CHARS = 5000;

    /** 关键词向量检索每条取回数（放宽阈值由 searchContent 的 minScore=0.2 承担）。 */
    private static final int KEYWORD_TOP_N = 4;

    private final EmbeddingService embeddingService;
    private final Executor prefetchExecutor;

    public ItemListRetriever(EmbeddingService embeddingService,
                             @org.springframework.beans.factory.annotation.Qualifier("qaPrefetchExecutor")
                             Executor prefetchExecutor) {
        this.embeddingService = embeddingService;
        this.prefetchExecutor = prefetchExecutor;
    }

    /**
     * 并行预取所有条目的检索材料。
     *
     * @return itemId → 该条材料文本；空列表或异常时为 ""（调用方据此走"资料不足"话术）
     */
    public Map<Integer, String> prefetchAll(Book book, List<AnswerOutline.Item> items) {
        Map<Integer, String> result = new LinkedHashMap<>();
        if (items == null || items.isEmpty()) {
            return result;
        }

        List<CompletableFuture<String[]>> futures = new ArrayList<>(items.size());
        for (int i = 0; i < items.size(); i++) {
            final int idx = i;
            futures.add(CompletableFuture.supplyAsync(() -> {
                try {
                    String material = retrieveOne(book, items.get(idx));
                    return new String[]{material};
                } catch (Exception e) {
                    log.warn("[Pipeline] 条目 {} 预取失败（该条走缺材料话术）: {}", idx + 1, e.getMessage());
                    return new String[]{""};
                }
            }, prefetchExecutor));
        }

        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
        for (int i = 0; i < futures.size(); i++) {
            AnswerOutline.Item item = items.get(i);
            int id = item.id() != null ? item.id() : (i + 1);
            String material = "";
            try {
                material = futures.get(i).join()[0];
            } catch (Exception e) {
                log.warn("[Pipeline] 条目 {} 预取 join 异常: {}", id, e.getMessage());
            }
            result.put(id, material != null ? material : "");
        }

        long hit = result.values().stream().filter(s -> !s.isBlank()).count();
        log.info("[Pipeline] 逐条预取完成: bookId={}, items={}, 有材料={}/{}",
                book.getId(), items.size(), hit, items.size());
        return result;
    }

    /**
     * 单条检索：锚点范围优先，缺失退化关键词向量检索 + 邻域扩展 + 合并相邻，最后按独立配额截断。
     */
    String retrieveOne(Book book, AnswerOutline.Item item) {
        // 1. 锚点优先（位置确定性最强，直接来自上一轮 RAG 材料的 chunkIndex 对齐）
        String anchored = retrieveByAnchors(book, item);
        if (!anchored.isBlank()) {
            return cap(anchored);
        }

        // 2. 关键词向量检索
        List<String> keywords = item.keywords();
        if (keywords == null) keywords = List.of();
        Map<Integer, EmbeddingMatch<TextSegment>> dedup = new LinkedHashMap<>();
        for (String kw : keywords) {
            if (kw == null || kw.isBlank()) continue;
            List<EmbeddingMatch<TextSegment>> matches =
                    embeddingService.searchContent(kw, KEYWORD_TOP_N, book);
            for (EmbeddingMatch<TextSegment> m : matches) {
                int idx = RagPipelineComponents.getChunkIndex(m);
                if (idx < 0) {
                    // 无 chunkIndex 元数据：用 embeddingId 兜底去重键，保证仍能进材料
                    String key = m.embeddingId() != null ? m.embeddingId() : ("txt:" + textOf(m).hashCode());
                    dedup.computeIfAbsent(Math.abs(key.hashCode()), k -> m);
                    continue;
                }
                EmbeddingMatch<TextSegment> exist = dedup.get(idx);
                if (exist == null || m.score() > exist.score()) {
                    dedup.put(idx, m);
                }
            }
        }
        if (dedup.isEmpty()) {
            return "";
        }

        List<EmbeddingMatch<TextSegment>> sorted = new ArrayList<>(dedup.values());
        sorted.sort(Comparator.comparingDouble(EmbeddingMatch<TextSegment>::score).reversed());
        List<EmbeddingMatch<TextSegment>> expanded = RagPipelineComponents.adaptiveNeighborExpand(
                sorted, book.getId(), 1, 2, embeddingService::searchContentByChunkIndex);
        List<EmbeddingMatch<TextSegment>> merged = RagPipelineComponents.mergeAdjacentChunks(expanded);

        // 3. 独立配额（truncateByQuota 而非全局 truncateToChars 的 break 语义，§4.6）
        return cap(RagPipelineComponents.truncateByQuota(merged, PER_ITEM_MAX_CHARS));
    }

    /**
     * 锚点范围检索：[min-1, max+2] scroll，命中即用。
     */
    private String retrieveByAnchors(Book book, AnswerOutline.Item item) {
        List<Integer> anchors = item.chunkAnchors();
        if (anchors == null || anchors.isEmpty()) {
            return "";
        }
        int min = anchors.stream().mapToInt(Integer::intValue).min().orElse(-1);
        int max = anchors.stream().mapToInt(Integer::intValue).max().orElse(-1);
        if (min < 0) return "";

        List<EmbeddingService.ChunkInfo> chunks =
                embeddingService.scrollChunksByIndexRange(book.getId(), Math.max(0, min - 1), max + 2);
        if (chunks.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (EmbeddingService.ChunkInfo c : chunks) {
            if (c.text() == null || c.text().isBlank()) continue;
            if (sb.length() + c.text().length() > PER_ITEM_MAX_CHARS) break;
            sb.append(c.text()).append("\n\n");
        }
        return sb.toString();
    }

    private String cap(String s) {
        return s.length() > PER_ITEM_MAX_CHARS ? s.substring(0, PER_ITEM_MAX_CHARS) : s;
    }

    private static String textOf(EmbeddingMatch<TextSegment> m) {
        return m.embedded() != null ? m.embedded().text() : "";
    }
}
