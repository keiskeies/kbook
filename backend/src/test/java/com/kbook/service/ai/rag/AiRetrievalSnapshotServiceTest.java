package com.kbook.service.ai.rag;

import com.kbook.entity.AiRetrievalSnapshot;
import com.kbook.repository.AiRetrievalSnapshotRepository;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 检索快照写入单测：只记唯一 chunk、字段完整、失败不影响主流程。
 */
class AiRetrievalSnapshotServiceTest {

    private AiRetrievalSnapshotRepository repository;
    private AiRetrievalSnapshotService service;
    private List<AiRetrievalSnapshot> captured;

    @BeforeEach
    void setUp() {
        repository = mock(AiRetrievalSnapshotRepository.class);
        service = new AiRetrievalSnapshotService(repository);
        captured = new ArrayList<>();
        when(repository.saveAll(anyList())).thenAnswer(inv -> {
            List<AiRetrievalSnapshot> rows = inv.getArgument(0);
            captured.addAll(rows);
            return rows;
        });
    }

    private static EmbeddingMatch<TextSegment> match(int chunkIndex, String text, double score) {
        Metadata metadata = new Metadata().put("chunkIndex", chunkIndex);
        return new EmbeddingMatch<>(score, String.valueOf(chunkIndex), null,
                TextSegment.from(text, metadata));
    }

    @Test
    @DisplayName("按 chunkIndex 去重，字段完整落库")
    void savesOneRowPerUniqueChunk() {
        service.save(10L, 20L, List.of(
                match(1, "第一段内容", 0.9),
                match(2, "第二段内容", 0.8),
                match(1, "重复命中", 0.7)), "v1");

        assertThat(captured).hasSize(2);
        assertThat(captured).extracting(AiRetrievalSnapshot::getChunkIndex)
                .containsExactly(1, 2);

        AiRetrievalSnapshot first = captured.get(0);
        assertThat(first.getConversationId()).isEqualTo(10L);
        assertThat(first.getBookId()).isEqualTo(20L);
        assertThat(first.getChunkEndIndex()).isEqualTo(1);
        assertThat(first.getScore()).isEqualTo(0.9);
        assertThat(first.getSource()).isEqualTo("context");
        assertThat(first.getTextPreview()).isEqualTo("第一段内容");
        assertThat(first.getTextHash()).hasSize(64);
        assertThat(first.getPromptVersion()).isEqualTo("v1");
    }

    @Test
    @DisplayName("无 conversationId 或空 matches 时不写")
    void skipsWhenNoTarget() {
        service.save(null, 20L, List.of(match(1, "abc", 0.9)), "v1");
        service.save(10L, 20L, List.of(), "v1");

        verify(repository, never()).saveAll(anyList());
        assertThat(captured).isEmpty();
    }

    @Test
    @DisplayName("无效 chunkIndex 跳过")
    void skipsInvalidChunkIndex() {
        service.save(10L, 20L, List.of(match(-1, "abc", 0.9)), "v1");

        assertThat(captured).isEmpty();
    }

    @Test
    @DisplayName("合并片段落库为起始/结束跨度（12+13 → 12..13）")
    void savesMergedChunkAsSpan() {
        List<EmbeddingMatch<TextSegment>> merged = RagPipelineComponents.mergeAdjacentChunks(List.of(
                match(12, "第一段".repeat(10), 0.9),
                match(13, "第二段".repeat(10), 0.8)));

        service.save(10L, 20L, merged, "v1");

        assertThat(captured).hasSize(1);
        assertThat(captured.get(0).getChunkIndex()).isEqualTo(12);
        assertThat(captured.get(0).getChunkEndIndex()).isEqualTo(13);
    }

    @Test
    @DisplayName("写入异常不抛穿主流程")
    void writeFailureDoesNotThrow() {
        when(repository.saveAll(anyList())).thenThrow(new RuntimeException("db down"));

        assertThatCode(() -> service.save(10L, 20L, List.of(match(1, "abc", 0.9)), "v1"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("长片段预览严格不超过 200 字符，不撑爆 VARCHAR(200)")
    void previewNeverExceedsColumnWidth() {
        String longText = "芒".repeat(1200);

        service.save(10L, 20L, List.of(match(1, longText, 0.9)), "v1");

        String preview = captured.get(0).getTextPreview();
        assertThat(preview).hasSize(200);
        assertThat(preview).endsWith("...");
    }

    @Test
    @DisplayName("短片段预览原样保留，null 安全")
    void previewKeepsShortText() {
        assertThat(AiRetrievalSnapshotService.preview("短内容")).isEqualTo("短内容");
        assertThat(AiRetrievalSnapshotService.preview("芒".repeat(200))).hasSize(200);
        assertThat(AiRetrievalSnapshotService.preview(null)).isNull();
    }

    @Test
    @DisplayName("SHA-256 稳定且对内容敏感")
    void sha256IsStable() {
        String h1 = AiRetrievalSnapshotService.sha256("abc");

        assertThat(h1).hasSize(64);
        assertThat(AiRetrievalSnapshotService.sha256("abc")).isEqualTo(h1);
        assertThat(AiRetrievalSnapshotService.sha256("abd")).isNotEqualTo(h1);
    }

    @Test
    @DisplayName("删除旧回答快照时委托仓储，失败不抛穿")
    void deleteByConversationIdDelegates() {
        service.deleteByConversationId(10L);
        verify(repository).deleteByConversationId(10L);

        service.deleteByConversationId(null);
        verify(repository, never()).deleteByConversationId(null);
    }

    @Test
    @DisplayName("按会话查询依据快照，空参数返回空列表")
    void findByConversationIdDelegates() {
        AiRetrievalSnapshot row = AiRetrievalSnapshot.builder()
                .id(1L).conversationId(10L).bookId(2L).chunkIndex(3).build();
        when(repository.findByConversationIdOrderByChunkIndexAsc(10L)).thenReturn(List.of(row));

        assertThat(service.findByConversationId(10L)).containsExactly(row);
        assertThat(service.findByConversationId(null)).isEmpty();
    }
}
