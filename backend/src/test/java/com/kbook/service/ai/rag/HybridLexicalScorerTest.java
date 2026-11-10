package com.kbook.service.ai.rag;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** P2-6：候选池内 BM25 词法重排 —— 精确词命中能被提上来，但绝不改原始 score。 */
class HybridLexicalScorerTest {

    private static EmbeddingMatch<TextSegment> hit(int idx, double score, String text) {
        return new EmbeddingMatch<>(score, "id-" + idx, null,
                TextSegment.from(text, new Metadata().put("chunkIndex", (long) idx)));
    }

    private static int chunkIndexOf(EmbeddingMatch<TextSegment> m) {
        return m.embedded().metadata().getLong("chunkIndex").intValue();
    }

    @Test
    void chineseBigramsAreDiscreteTokens() {
        List<String> tokens = HybridLexicalScorer.tokenize("复利效应 与 Compound Interest 2024");
        assertTrue(tokens.contains("复利"));
        assertTrue(tokens.contains("效应"));
        assertTrue(tokens.contains("compound"));
        assertTrue(tokens.contains("interest"));
        assertTrue(tokens.contains("2024"));
        assertFalse(tokens.contains("复利效应 与"), "不能把整句当成一个词");
    }

    @Test
    void bm25PrefersExactTermMatch() {
        List<String> docs = List.of(
                "这一段讲普通的阅读方法和做笔记的习惯，与复利无关",
                "复利效应是长期投资的核心，复利效应需要时间积累，复利效应还会被税费侵蚀",
                "另一段关于人物性格的描写");
        double[] scores = HybridLexicalScorer.bm25Scores(docs, "复利效应");
        assertEquals(3, scores.length);
        assertTrue(scores[1] > scores[0], "含查询词的块应得分更高");
        assertEquals(0.0, scores[2], 1e-9);
    }

    @Test
    void rerankPromotesLexicalHitWithoutChangingScores() {
        EmbeddingMatch<TextSegment> denseTop = hit(1, 0.92, "这一段讲普通的阅读方法和做笔记的习惯");
        EmbeddingMatch<TextSegment> lexicalHit = hit(2, 0.61,
                "复利效应是长期投资的核心，复利效应需要时间积累，复利效应还会被税费侵蚀");
        EmbeddingMatch<TextSegment> denseTail = hit(3, 0.44, "另一段关于人物性格的描写");

        List<EmbeddingMatch<TextSegment>> reranked = HybridLexicalScorer.rerankRoute(
                List.of(denseTop, lexicalHit, denseTail), "复利效应", 0.8);

        assertEquals(3, reranked.size());
        assertEquals(2, chunkIndexOf(reranked.get(0)), "强词法权重下精确命中应浮到前面");
        assertEquals(0.92, reranked.stream()
                .filter(m -> chunkIndexOf(m) == 1).findFirst().orElseThrow().score(), 1e-9,
                "原始向量分必须原样保留");
        assertSame(denseTop, reranked.stream().filter(m -> chunkIndexOf(m) == 1).findFirst().orElseThrow(),
                "复用原 match 对象，避免下游 metadata/embedding 丢失");
    }

    @Test
    void zeroWeightKeepsOriginalOrder() {
        List<EmbeddingMatch<TextSegment>> hits = List.of(
                hit(1, 0.9, "复利效应"), hit(2, 0.5, "别的"));
        assertSame(hits, HybridLexicalScorer.rerankRoute(hits, "复利效应", 0.0));
    }

    @Test
    void noMatchingTermsKeepsDenseOrder() {
        List<EmbeddingMatch<TextSegment>> hits = List.of(
                hit(1, 0.9, "第一段内容"), hit(2, 0.5, "第二段内容"), hit(3, 0.2, "第三段内容"));
        List<EmbeddingMatch<TextSegment>> out = HybridLexicalScorer.rerankRoute(hits, "量子纠缠", 0.9);
        assertEquals(List.of(1, 2, 3), out.stream().map(HybridLexicalScorerTest::chunkIndexOf).toList());
    }

    @Test
    void nullEmptyAndSingleAreSafe() {
        assertTrue(HybridLexicalScorer.rerankRoute(null, "x", 0.5) == null);
        assertEquals(1, HybridLexicalScorer.rerankRoute(
                List.of(hit(1, 0.9, "复利效应")), "复利效应", 0.5).size());
        assertEquals(0, HybridLexicalScorer.bm25Scores(null, "x").length);
        assertTrue(HybridLexicalScorer.tokenize(null).isEmpty());
    }
}
