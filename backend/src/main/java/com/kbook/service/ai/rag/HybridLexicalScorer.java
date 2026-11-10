package com.kbook.service.ai.rag;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 混合召回的词法侧：在 dense 候选池内做 BM25 重排（方案 P2-6）。
 *
 * <p><b>为什么是「候选池内」而不是全库倒排</b>：方案 §3 明确 P2-6 并入桌面化
 * 路径 B 的 Lucene 内嵌改造，不单独立项 —— 全库 BM25 需要 chunk 倒排索引，
 * 会引入「chunk 索引 + 双写一致性」两大障碍。这里先落地不依赖额外存储、
 * 立刻可用的形态：把每路 dense 候选池放大，用 BM25 精确词匹配给候选重排，
 * 再按名次与 dense 名次加权融合，交给已有的 RRF 做跨路汇总。
 *
 * <p><b>关键约束</b>：只改候选顺序，绝不动 {@code EmbeddingMatch.score()}。
 * 下游邻域扩展、合并、{@code BookAnswerGuard.topScore}、检索快照与历史 A/B 报告
 * 的口径全部保持不变。开关关闭时本类完全不参与。
 *
 * <p>中文没有空格，采用「汉字二元组 + 英文单词 + 数字串」的确定性分词；
 * 不引入分词器依赖，也不调用 LLM。真正的倒排索引与中文分词器留给 Lucene 改造。
 */
public final class HybridLexicalScorer {

    /** 与 {@code BookChatRagService.RRF_K} 同一量级，避免头部名次被过度放大 */
    private static final int RANK_K = 60;
    private static final double K1 = 1.5;
    private static final double B = 0.75;
    private static final int MAX_DOC_CHARS = 5000;
    private static final int MAX_QUERY_TERMS = 32;

    private HybridLexicalScorer() {}

    /**
     * 按「dense 名次分 × (1-w) + 词法名次分 × w」重排某一路候选。
     *
     * @param hits          该路 dense 命中（已按相似度降序）
     * @param query         该路子查询
     * @param lexicalWeight 词法权重，0~1；0 时原样返回
     */
    public static List<EmbeddingMatch<TextSegment>> rerankRoute(
            List<EmbeddingMatch<TextSegment>> hits, String query, double lexicalWeight) {
        if (hits == null || hits.size() < 2 || query == null || query.isBlank()) return hits;
        double w = Math.max(0.0, Math.min(1.0, lexicalWeight));
        if (w <= 0.0) return hits;

        List<String> docs = new ArrayList<>(hits.size());
        for (EmbeddingMatch<TextSegment> h : hits) {
            String text = h.embedded() == null ? "" : h.embedded().text();
            docs.add(text == null ? "" : text);
        }
        double[] bm25 = bm25Scores(docs, query);

        // 词法名次：BM25 降序；全 0 时保持 dense 原序（稳定排序）
        List<Integer> lexicalOrder = new ArrayList<>(hits.size());
        for (int i = 0; i < hits.size(); i++) lexicalOrder.add(i);
        lexicalOrder.sort(Comparator.<Integer>comparingDouble(i -> bm25[i]).reversed()
                .thenComparingInt(i -> i));
        double[] lexicalRank = new double[hits.size()];
        for (int rank = 0; rank < lexicalOrder.size(); rank++) {
            lexicalRank[lexicalOrder.get(rank)] = 1.0 / (RANK_K + rank + 1.0);
        }

        List<Integer> denseOrder = new ArrayList<>(hits.size());
        for (int i = 0; i < hits.size(); i++) denseOrder.add(i);
        denseOrder.sort(Comparator.<Integer>comparingDouble(i -> {
            double denseRankScore = 1.0 / (RANK_K + i + 1.0);
            return (1.0 - w) * denseRankScore + w * lexicalRank[i];
        }).reversed().thenComparingInt(i -> i));

        List<EmbeddingMatch<TextSegment>> out = new ArrayList<>(hits.size());
        for (int i : denseOrder) out.add(hits.get(i));
        return out;
    }

    /** BM25 打分（包级可见，便于单测直接断言词法信号）。 */
    static double[] bm25Scores(List<String> docs, String query) {
        double[] scores = new double[docs == null ? 0 : docs.size()];
        if (docs == null || docs.isEmpty() || query == null || query.isBlank()) return scores;

        Set<String> queryTerms = new java.util.LinkedHashSet<>(tokenize(query));
        if (queryTerms.isEmpty()) return scores;
        if (queryTerms.size() > MAX_QUERY_TERMS) {
            queryTerms = new java.util.LinkedHashSet<>(
                    new ArrayList<>(queryTerms).subList(0, MAX_QUERY_TERMS));
        }

        List<Map<String, Integer>> tfs = new ArrayList<>(docs.size());
        int[] docLen = new int[docs.size()];
        Map<String, Integer> df = new HashMap<>();
        long totalLen = 0;
        for (int i = 0; i < docs.size(); i++) {
            String text = docs.get(i) == null ? "" : docs.get(i);
            if (text.length() > MAX_DOC_CHARS) text = text.substring(0, MAX_DOC_CHARS);
            List<String> tokens = tokenize(text);
            docLen[i] = Math.max(1, tokens.size());
            totalLen += docLen[i];
            Map<String, Integer> tf = new HashMap<>();
            for (String t : tokens) tf.merge(t, 1, Integer::sum);
            tfs.add(tf);
            for (String t : tf.keySet()) {
                if (queryTerms.contains(t)) df.merge(t, 1, Integer::sum);
            }
        }
        double avgdl = Math.max(1.0, (double) totalLen / docs.size());
        int n = docs.size();

        for (int i = 0; i < n; i++) {
            double score = 0.0;
            Map<String, Integer> tf = tfs.get(i);
            for (String term : queryTerms) {
                int freq = tf.getOrDefault(term, 0);
                if (freq == 0) continue;
                int dfi = df.getOrDefault(term, 0);
                double idf = Math.log(1.0 + (n - dfi + 0.5) / (dfi + 0.5));
                double norm = freq + K1 * (1.0 - B + B * docLen[i] / avgdl);
                score += idf * (freq * (K1 + 1.0)) / norm;
            }
            scores[i] = score;
        }
        return scores;
    }

    /** 汉字二元组 + 英文单词 + 数字串；只输出离散词，不做任何改写。 */
    static List<String> tokenize(String text) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isEmpty()) return out;
        String lower = text.toLowerCase(Locale.ROOT);
        int i = 0;
        while (i < lower.length()) {
            char c = lower.charAt(i);
            if (c >= 'a' && c <= 'z') {
                int j = i + 1;
                while (j < lower.length() && lower.charAt(j) >= 'a' && lower.charAt(j) <= 'z') j++;
                if (j - i >= 2) out.add(lower.substring(i, j));
                i = j;
            } else if (c >= '0' && c <= '9') {
                int j = i + 1;
                while (j < lower.length() && lower.charAt(j) >= '0' && lower.charAt(j) <= '9') j++;
                if (j - i >= 2) out.add(lower.substring(i, j));
                i = j;
            } else if (c >= '\u4e00' && c <= '\u9fff') {
                // 相邻汉字组成二元组；跨标点/空白不连
                if (i + 1 < lower.length()) {
                    char next = lower.charAt(i + 1);
                    if (next >= '\u4e00' && next <= '\u9fff') out.add("" + c + next);
                }
                i++;
            } else {
                i++;
            }
        }
        return out;
    }
}
