package com.kbook.service.ai.rag;

import com.kbook.service.embedding.EmbeddingIndexService.ChunkInfo;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * 列表精筛排序/截断单测：保序、按 rank 截断、文档顺序输出。
 */
class ChunkRankingTest {

    private static ChunkInfo chunk(int index, String text) {
        return new ChunkInfo(text, index, index);
    }

    @Test
    @DisplayName("保留 LLM 选择顺序，忽略未知/重复编号")
    void orderSelectedPreservesLlmOrder() {
        List<ChunkInfo> candidates = List.of(chunk(1, "A"), chunk(2, "B"), chunk(3, "C"));

        List<ChunkInfo> ordered = ChunkRanking.orderSelected(candidates, List.of(3, 1, 3, 99));

        assertThat(ordered).extracting(ChunkInfo::chunkIndex).containsExactly(3, 1);
    }

    @Test
    @DisplayName("按 rank 赋分，输出时按文档顺序排列")
    void toMatchesUsesRankScoresAndDocumentOrder() {
        List<EmbeddingMatch<dev.langchain4j.data.segment.TextSegment>> matches =
                ChunkRanking.toMatches(List.of(
                        chunk(5, "A".repeat(100)),
                        chunk(2, "B".repeat(100)),
                        chunk(10, "C".repeat(100))), 1L, 1000);

        assertThat(matches)
                .extracting(RagPipelineComponents::getChunkIndex)
                .containsExactly(2, 5, 10);

        Map<Integer, Double> scores = matches.stream()
                .collect(Collectors.toMap(RagPipelineComponents::getChunkIndex, EmbeddingMatch::score));
        assertThat(scores.get(5)).isEqualTo(1.0);
        assertThat(scores.get(2)).isEqualTo(0.5);
        assertThat(scores.get(10)).isCloseTo(1.0 / 3, within(1e-9));
    }

    @Test
    @DisplayName("精筛选中但靠后的条目不会因文档顺序被砍掉")
    void keepsLateSmallChunkWhenEarlierSkipped() {
        List<EmbeddingMatch<dev.langchain4j.data.segment.TextSegment>> matches =
                ChunkRanking.toMatches(List.of(
                        chunk(1, "A".repeat(300)),
                        chunk(2, "B".repeat(300)),
                        chunk(3, "C".repeat(20))), 1L, 350);

        assertThat(matches)
                .extracting(RagPipelineComponents::getChunkIndex)
                .containsExactly(1, 3);
    }

    @Test
    @DisplayName("第一个块超预算仍保留，避免空上下文")
    void keepsFirstOversizedChunk() {
        List<EmbeddingMatch<dev.langchain4j.data.segment.TextSegment>> matches =
                ChunkRanking.toMatches(List.of(chunk(1, "A".repeat(500))), 1L, 100);

        assertThat(matches).hasSize(1);
    }

    @Test
    @DisplayName("空文本跳过，空输入返回空列表")
    void skipsBlankAndEmpty() {
        assertThat(ChunkRanking.toMatches(List.of(
                chunk(1, "  "), chunk(2, "B")), 1L, 100))
                .extracting(RagPipelineComponents::getChunkIndex)
                .containsExactly(2);

        assertThat(ChunkRanking.toMatches(List.of(), 1L, 100)).isEmpty();
        assertThat(ChunkRanking.orderSelected(List.of(), List.of(1))).isEmpty();
    }
}
