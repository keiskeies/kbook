package com.kbook.service.ai.rag;

import com.kbook.service.embedding.EmbeddingIndexService.ChunkInfo;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 列表精筛结果的排序与截断。
 * <p>
 * 精筛返回的是"相关 chunk 的顺序"，这个顺序本身就是相关性信号。旧实现按 chunkIndex
 * 升序截断，会把精筛选中但位置靠后的条目整段砍掉。这里改为：
 * <ol>
 *   <li>保留 LLM 选择顺序；</li>
 *   <li>分数按排名赋值（1/(rank+1)），供快照记录；</li>
 *   <li>按 rank 顺序装入预算，放不下就跳过继续尝试后续较小块；</li>
 *   <li>最终按 chunkIndex 排序，保证上下文按原文顺序可读。</li>
 * </ol>
 */
public final class ChunkRanking {

    private ChunkRanking() {
    }

    /** 按 LLM 输出顺序取回候选 chunk；未知/重复编号忽略。 */
    public static List<ChunkInfo> orderSelected(List<ChunkInfo> candidates, List<Integer> selectedIndices) {
        if (candidates == null || candidates.isEmpty()
                || selectedIndices == null || selectedIndices.isEmpty()) {
            return List.of();
        }
        Map<Integer, ChunkInfo> byIndex = new LinkedHashMap<>();
        for (ChunkInfo candidate : candidates) {
            byIndex.put(candidate.chunkIndex(), candidate);
        }
        List<ChunkInfo> ordered = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        for (Integer index : selectedIndices) {
            if (index == null || !seen.add(index)) continue;
            ChunkInfo candidate = byIndex.get(index);
            if (candidate != null) {
                ordered.add(candidate);
            }
        }
        return ordered;
    }

    /**
     * rank 顺序截断 + 文档顺序输出。
     * <p>第一个块超预算时仍保留（避免空上下文）；后续块放不下则跳过，继续尝试较小块。
     */
    public static List<EmbeddingMatch<TextSegment>> toMatches(List<ChunkInfo> chunks,
                                                              long bookId, int maxChars) {
        if (chunks == null || chunks.isEmpty()) {
            return List.of();
        }
        List<EmbeddingMatch<TextSegment>> selected = new ArrayList<>();
        int total = 0;
        for (int rank = 0; rank < chunks.size(); rank++) {
            ChunkInfo chunk = chunks.get(rank);
            String text = chunk.text();
            if (text == null || text.isBlank()) continue;
            if (total > 0 && total + text.length() > maxChars) {
                continue;
            }
            Metadata metadata = new Metadata()
                    .put("bookId", bookId)
                    .put("chunkIndex", chunk.chunkIndex());
            selected.add(new EmbeddingMatch<>(1.0 / (rank + 1),
                    String.valueOf(chunk.pointId()), null, TextSegment.from(text, metadata)));
            total += text.length();
        }
        selected.sort(Comparator.comparingInt(RagPipelineComponents::getChunkIndex));
        return selected;
    }
}
