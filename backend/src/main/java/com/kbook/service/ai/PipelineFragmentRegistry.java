package com.kbook.service.ai;

import com.kbook.service.ai.rag.ItemListRetriever.Material;
import com.kbook.service.ai.rag.RagPipelineComponents;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 流水线全局片段编号：跨条目给 chunk 分配稳定编号。
 * <p>
 * 流水线逐节生成，材料按节到达；本类在每节生成前把该节材料编号并拼接。
 * 同一 chunk 在多条材料里出现时复用同一编号，保证最终回答里的 [[N]] 全局可校验。
 */
final class PipelineFragmentRegistry {

    private final Map<Integer, Integer> byChunkIndex = new HashMap<>();
    private final List<EmbeddingMatch<TextSegment>> all = new ArrayList<>();
    private int next = 1;

    /** 给一条材料加全局编号并拼接；空材料返回 ""。 */
    String label(Material material) {
        if (material == null || material.matches() == null || material.matches().isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (EmbeddingMatch<TextSegment> match : material.matches()) {
            if (match == null) continue;
            String text = match.embedded() != null ? match.embedded().text() : "";
            if (text == null || text.isBlank()) continue;

            int chunkIndex = RagPipelineComponents.getChunkIndex(match);
            if (chunkIndex < 0) {
                // 没有 chunkIndex 的片段落不了证据行（快照按 chunkIndex 跳过负值），
                // 一旦给它编号，"编号上界"就会比证据行数多 → 最后一个编号永远被判非法
                // （实测 25 节长答案 [14] vs 13 行证据，citationValidity 0.949）。
                // 文本仍进材料供参考，但不编号、不可被引用。
                sb.append(text).append("\n\n");
                continue;
            }
            Integer number = byChunkIndex.get(chunkIndex);
            if (number == null) {
                number = next++;
                byChunkIndex.put(chunkIndex, number);
                all.add(match);
            }
            // 同一片段再次出现时保留文本（本节仍需上下文），但编号复用
            sb.append(RagPipelineComponents.fragmentLabel(match, number))
              .append("\n").append(text).append("\n\n");
        }
        return sb.toString();
    }

    /** 已分配的全局片段数（引用的合法上界）。 */
    int size() {
        return next - 1;
    }

    /** 去重后的全部依据片段（供检索快照）。 */
    List<EmbeddingMatch<TextSegment>> all() {
        return List.copyOf(all);
    }

}
