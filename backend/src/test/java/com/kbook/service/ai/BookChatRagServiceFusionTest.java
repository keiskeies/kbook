package com.kbook.service.ai;

import com.kbook.service.ai.rag.RagPipelineComponents;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 分路 RRF 融合：验证跨路公平性、路内相对分与原始分保持不变。 */
class BookChatRagServiceFusionTest {

    private static EmbeddingMatch<TextSegment> hit(int chunkIndex, double score) {
        dev.langchain4j.data.document.Metadata metadata =
                new dev.langchain4j.data.document.Metadata().put("chunkIndex", (long) chunkIndex);
        return new EmbeddingMatch<>(score, "id-" + chunkIndex, null,
                TextSegment.from("文本" + chunkIndex, metadata));
    }

    @Test
    void crossRouteConsensusOutranksSingleRouteTopHit() {
        // 路1: A 第1(0.9), B 第2(0.2)
        // 路2: B 第1(0.6), C 第2(0.5)
        // 旧的全局混排会让 A(0.9) 稳居第一；RRF 应让两路都靠前的 B 胜出。
        List<List<EmbeddingMatch<TextSegment>>> routes = List.of(
                List.of(hit(1, 0.9), hit(2, 0.2)),
                List.of(hit(2, 0.6), hit(3, 0.5)));

        List<BookChatRagService.FusedHit> fused = BookChatRagService.fuseSubQueryHits(routes);
        fused.sort((a, b) -> Double.compare(b.rrfScore(), a.rrfScore()));
        assertEquals(2, RagPipelineComponents.getChunkIndex(fused.get(0).match()));
        assertEquals(3, fused.size());
    }

    @Test
    void relativeScoreUsesOwnRouteMaximum() {
        // 路2 的头名是 0.6；块 3 的 0.5 应得到 0.833 的相对分，
        // 而不是拿全局最高分 0.9 当分母（那样只有 0.556）。
        List<List<EmbeddingMatch<TextSegment>>> routes = List.of(
                List.of(hit(1, 0.9)),
                List.of(hit(2, 0.6), hit(3, 0.5)));

        List<BookChatRagService.FusedHit> fused = BookChatRagService.fuseSubQueryHits(routes);
        BookChatRagService.FusedHit chunk3 = fused.stream()
                .filter(h -> RagPipelineComponents.getChunkIndex(h.match()) == 3)
                .findFirst().orElseThrow();
        assertEquals(0.5 / 0.6, chunk3.routeRelativeScore(), 1e-9);
        assertTrue(chunk3.routeRelativeScore() > 0.3, "不应被全局分数分布误裁");
    }

    @Test
    void originalScoreIsPreservedForDownstream() {
        EmbeddingMatch<TextSegment> a = hit(7, 0.88);
        List<BookChatRagService.FusedHit> fused =
                BookChatRagService.fuseSubQueryHits(List.of(List.of(a)));
        assertSame(a, fused.get(0).match());
        assertEquals(0.88, fused.get(0).match().score(), 1e-9);
    }

    @Test
    void nonPositiveRouteIsNotFilteredOut() {
        List<List<EmbeddingMatch<TextSegment>>> routes = List.of(
                List.of(hit(1, -0.2), hit(2, -0.5)));
        List<BookChatRagService.FusedHit> fused = BookChatRagService.fuseSubQueryHits(routes);
        assertEquals(2, fused.size());
        assertTrue(fused.stream().allMatch(h -> h.routeRelativeScore() >= 0.3));
    }

    @Test
    void nullAndEmptyRoutesAreSafe() {
        assertTrue(BookChatRagService.fuseSubQueryHits(null).isEmpty());
        assertTrue(BookChatRagService.fuseSubQueryHits(List.of()).isEmpty());
        assertTrue(BookChatRagService.fuseSubQueryHits(List.of(List.of())).isEmpty());
    }

    @Test
    void queryLimitKeepsOriginalQueryFirst() {
        List<String> queries = new java.util.ArrayList<>(
                List.of("原始问题", "关键词", "q3", "q4", "q5", "q6", "q7", "q8"));
        List<String> limited = BookChatRagService.limitQueries(queries);
        assertEquals(6, limited.size());
        assertEquals("原始问题", limited.get(0));
    }
}
