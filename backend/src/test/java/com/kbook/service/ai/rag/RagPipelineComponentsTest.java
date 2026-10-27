package com.kbook.service.ai.rag;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RAG 合并组件单测：相邻 chunk 合并时不能把 overlap 重复拼进上下文。
 */
class RagPipelineComponentsTest {

    private static EmbeddingMatch<TextSegment> match(int chunkIndex, String text, double score) {
        Metadata metadata = new Metadata().put("chunkIndex", chunkIndex);
        return new EmbeddingMatch<>(score, String.valueOf(chunkIndex), null,
                TextSegment.from(text, metadata));
    }

    @Test
    @DisplayName("相邻 chunk 合并时去掉重叠文本，只出现一次")
    void mergeAdjacentChunksStripsOverlap() {
        String overlap = "重叠内容".repeat(20);
        String prevText = "A".repeat(300) + overlap;
        String nextText = overlap + "B".repeat(300);

        List<EmbeddingMatch<TextSegment>> merged = RagPipelineComponents.mergeAdjacentChunks(List.of(
                match(1, prevText, 0.9),
                match(2, nextText, 0.8)));

        assertThat(merged).hasSize(1);
        String text = merged.get(0).embedded().text();
        assertThat(text).contains("A".repeat(300)).contains("B".repeat(300));

        int first = text.indexOf(overlap);
        int last = text.lastIndexOf(overlap);
        assertThat(first).isNotNegative();
        assertThat(last).as("overlap 只应出现一次").isEqualTo(first);
    }

    @Test
    @DisplayName("非连续 chunk 不合并")
    void nonConsecutiveChunksAreNotMerged() {
        List<EmbeddingMatch<TextSegment>> merged = RagPipelineComponents.mergeAdjacentChunks(List.of(
                match(1, "第一段", 0.9),
                match(3, "第三段", 0.8)));

        assertThat(merged).hasSize(2);
    }

    @Test
    @DisplayName("没有重叠时原样追加")
    void noOverlapAppendsFullText() {
        List<EmbeddingMatch<TextSegment>> merged = RagPipelineComponents.mergeAdjacentChunks(List.of(
                match(1, "AAAA", 0.9),
                match(2, "BBBB", 0.8)));

        assertThat(merged).hasSize(1);
        assertThat(merged.get(0).embedded().text()).isEqualTo("AAAA\n\nBBBB");
    }

    @Test
    @DisplayName("stripOverlap 只剥离真实的重叠后缀")
    void stripOverlapOnlyRemovesRealOverlap() {
        assertThat(RagPipelineComponents.stripOverlap("abcXYZ", "XYZdef")).isEqualTo("def");
        assertThat(RagPipelineComponents.stripOverlap("abc", "def")).isEqualTo("def");
        assertThat(RagPipelineComponents.stripOverlap("abc", "abc")).isEmpty();
        assertThat(RagPipelineComponents.stripOverlap(null, "abc")).isEqualTo("abc");
    }

    @Test
    @DisplayName("预算选取返回真正入选的 matches（快照依据）")
    void selectWithinBudgetReturnsIncludedMatches() {
        EmbeddingMatch<TextSegment> a = match(1, "A".repeat(100), 0.9);
        EmbeddingMatch<TextSegment> b = match(2, "B".repeat(100), 0.8);
        EmbeddingMatch<TextSegment> c = match(3, "C".repeat(100), 0.7);

        RagPipelineComponents.Selection selection =
                RagPipelineComponents.selectWithinBudget(List.of(a, b, c), 250);

        assertThat(selection.matches())
                .extracting(RagPipelineComponents::getChunkIndex)
                .containsExactly(1, 2);
        assertThat(selection.text())
                .contains("A".repeat(100))
                .contains("B".repeat(100))
                .doesNotContain("C".repeat(100));

        assertThat(RagPipelineComponents.selectWithinBudget(List.of(a), 0).matches()).isEmpty();
    }

    @Test
    @DisplayName("配额选取跳过放不下的大块，继续保留后面的小块")
    void selectByQuotaSkipsOversizedChunk() {
        RagPipelineComponents.Selection selection = RagPipelineComponents.selectByQuota(List.of(
                match(1, "A".repeat(100), 0.9),
                match(2, "B".repeat(300), 0.8),
                match(3, "C".repeat(100), 0.7)), 250);

        assertThat(selection.matches())
                .extracting(RagPipelineComponents::getChunkIndex)
                .containsExactly(1, 3);
        assertThat(selection.text())
                .contains("A".repeat(100))
                .contains("C".repeat(100))
                .doesNotContain("B".repeat(300));
    }

    @Test
    @DisplayName("带编号选取时片段顺序与 matches 一致")
    void labeledSelectionNumbersIncludedChunks() {
        RagPipelineComponents.Selection selection = RagPipelineComponents.selectWithinBudget(List.of(
                match(1, "第一段", 0.9),
                match(2, "第二段", 0.8)), 500, true);

        assertThat(selection.text())
                .contains("【片段 1】")
                .contains("【片段 2】");
        assertThat(selection.matches())
                .extracting(RagPipelineComponents::getChunkIndex)
                .containsExactly(1, 2);
    }

    @Test
    @DisplayName("有章节元数据时片段标签带章节")
    void fragmentLabelIncludesChapter() {
        Metadata metadata = new Metadata()
                .put("chunkIndex", 1)
                .put("chapterTitle", "第二章 转折");
        EmbeddingMatch<TextSegment> match = new EmbeddingMatch<>(0.9, "1", null,
                TextSegment.from("x", metadata));

        assertThat(RagPipelineComponents.fragmentLabel(match, 3))
                .isEqualTo("【片段 3｜第二章 转折】");
    }

    @Test
    @DisplayName("合并后字符数仍受上限约束")
    void mergeRespectsMaxChars() {
        String big = "X".repeat(1200);
        List<EmbeddingMatch<TextSegment>> merged = RagPipelineComponents.mergeAdjacentChunks(List.of(
                match(1, big, 0.9),
                match(2, big, 0.8)));

        assertThat(merged).hasSize(2);
        assertThat(merged).allSatisfy(m ->
                assertThat(m.embedded().text().length())
                        .isLessThanOrEqualTo(RagPipelineComponents.MAX_MERGED_CHUNK_CHARS));
    }

    @Test
    @DisplayName("合并片段保留跨度：12+13 → 起始 12、结束 13")
    void mergedChunkKeepsSpan() {
        List<EmbeddingMatch<TextSegment>> merged = RagPipelineComponents.mergeAdjacentChunks(List.of(
                match(12, "第一段".repeat(10), 0.9),
                match(13, "第二段".repeat(10), 0.8)));

        assertThat(merged).hasSize(1);
        assertThat(RagPipelineComponents.getChunkSpan(merged.get(0)))
                .containsExactly(12, 13);
    }

    @Test
    @DisplayName("不连续 chunk 各自记自己的跨度")
    void nonConsecutiveChunksKeepOwnSpan() {
        List<EmbeddingMatch<TextSegment>> merged = RagPipelineComponents.mergeAdjacentChunks(List.of(
                match(12, "第一段".repeat(10), 0.9),
                match(14, "第三段".repeat(10), 0.8)));

        assertThat(merged).hasSize(2);
        assertThat(RagPipelineComponents.getChunkSpan(merged.get(0))).containsExactly(12, 12);
        assertThat(RagPipelineComponents.getChunkSpan(merged.get(1))).containsExactly(14, 14);
    }

    @Test
    @DisplayName("合并不污染原始 chunk 的元数据")
    void mergeDoesNotMutateSourceMetadata() {
        EmbeddingMatch<TextSegment> first = match(12, "第一段".repeat(10), 0.9);
        EmbeddingMatch<TextSegment> second = match(13, "第二段".repeat(10), 0.8);

        RagPipelineComponents.mergeAdjacentChunks(List.of(first, second));

        assertThat(first.embedded().metadata().containsKey(RagPipelineComponents.META_CHUNK_START_INDEX))
                .isFalse();
        assertThat(second.embedded().metadata().containsKey(RagPipelineComponents.META_CHUNK_END_INDEX))
                .isFalse();
    }

    @Test
    @DisplayName("没有跨度元数据时按单个 chunk 处理")
    void spanFallsBackToChunkIndex() {
        assertThat(RagPipelineComponents.getChunkSpan(match(7, "单独一块", 0.9)))
                .containsExactly(7, 7);
    }
}
