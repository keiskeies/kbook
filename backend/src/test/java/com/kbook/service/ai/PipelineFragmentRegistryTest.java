package com.kbook.service.ai;

import com.kbook.service.ai.rag.ItemListRetriever.Material;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 流水线全局片段编号单测：跨条目稳定、重复 chunk 复用编号、快照去重。
 */
class PipelineFragmentRegistryTest {

    private static EmbeddingMatch<TextSegment> match(int chunkIndex, String text, String embeddingId) {
        Metadata metadata = new Metadata().put("chunkIndex", chunkIndex);
        return new EmbeddingMatch<>(0.9, embeddingId, null, TextSegment.from(text, metadata));
    }

    @Test
    @DisplayName("跨条目编号连续，重复 chunk 复用同一编号")
    void globalNumberingIsStableAcrossItems() {
        PipelineFragmentRegistry registry = new PipelineFragmentRegistry();

        String first = registry.label(new Material("", List.of(
                match(12, "A段", "1"),
                match(13, "B段", "2"))));
        String second = registry.label(new Material("", List.of(
                match(13, "B段", "2"),
                match(14, "C段", "3"))));

        assertThat(first).contains("【片段 1】\nA段").contains("【片段 2】\nB段");
        assertThat(second).contains("【片段 2】\nB段").contains("【片段 3】\nC段");
        assertThat(registry.size()).isEqualTo(3);
        assertThat(registry.all()).hasSize(3);
    }

    @Test
    @DisplayName("无 chunkIndex 的片段不编号：编号上界必须等于证据行数")
    void unnumberedWhenChunkIndexMissing() {
        PipelineFragmentRegistry registry = new PipelineFragmentRegistry();
        Metadata noIndex = new Metadata();

        // 快照按 chunkIndex 落行、负值直接跳过；给这种片段编号会让上界比证据行数多 1，
        // 最后一个编号永远被判非法（实测 25 节答案 [14] vs 13 行，citationValidity 0.949）
        String text = registry.label(new Material("", List.of(
                new EmbeddingMatch<>(0.9, "same-id", null, TextSegment.from("D段", noIndex)))));

        assertThat(text).contains("D段");
        assertThat(text).doesNotContain("【片段");
        assertThat(registry.size()).isZero();
        assertThat(registry.all()).isEmpty();

        // 有 chunkIndex 的片段照常编号，且编号从 1 开始保持连续
        String numbered = registry.label(new Material("", List.of(
                match(7, "E段", "e"))));
        assertThat(numbered).contains("【片段 1】");
        assertThat(registry.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("空材料不分配编号")
    void emptyMaterialDoesNotAllocate() {
        PipelineFragmentRegistry registry = new PipelineFragmentRegistry();

        assertThat(registry.label(Material.empty())).isEmpty();
        assertThat(registry.label(null)).isEmpty();
        assertThat(registry.size()).isZero();
        assertThat(registry.all()).isEmpty();
    }
}
