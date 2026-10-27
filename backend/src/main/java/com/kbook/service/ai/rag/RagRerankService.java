package com.kbook.service.ai.rag;

import com.kbook.config.ChatModelFactory;
import com.kbook.config.properties.QdrantProperties;
import com.kbook.entity.AiScene;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LLM 重排：对已合并的候选片段按"是否直接回答读者问题"打 0~3 分并重排。
 * <p>
 * 默认关闭（{@code qdrant.rerank-enabled=false}），因为每轮会增加多次 TOOL 调用；
 * 压测延迟与成本后再开启。向量分保留在 {@link EmbeddingMatch#score()}，重排分写入
 * metadata 的 {@code rerankScore}，供检索快照记录。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagRerankService {

    private static final int BATCH_SIZE = 10;
    private static final int PREVIEW_CHARS = 300;
    private static final Pattern SCORE_LINE =
            Pattern.compile("(?m)^\\s*\\[?(\\d+)\\]?\\s*[:：]\\s*([0-3])\\s*$");

    private final ChatModelFactory chatModelFactory;
    private final QdrantProperties qdrantProperties;

    public List<EmbeddingMatch<TextSegment>> rerank(String question,
                                                    List<EmbeddingMatch<TextSegment>> matches) {
        if (!qdrantProperties.isRerankEnabled()
                || question == null || question.isBlank()
                || matches == null || matches.size() <= 1) {
            return matches;
        }

        int topN = Math.max(1, qdrantProperties.getRerankTopN());
        int candidateCount = Math.min(topN, matches.size());
        List<EmbeddingMatch<TextSegment>> candidates = new ArrayList<>(
                matches.subList(0, candidateCount));
        List<EmbeddingMatch<TextSegment>> tail = matches.size() > topN
                ? new ArrayList<>(matches.subList(topN, matches.size())) : List.of();

        Map<Integer, Double> scores = score(question, candidates);
        if (scores.isEmpty()) {
            log.info("[Rerank] 无有效分数，保持向量顺序: candidates={}", candidates.size());
            return matches;
        }

        candidates.sort(Comparator
                .comparingDouble((EmbeddingMatch<TextSegment> m) ->
                        scores.getOrDefault(RagPipelineComponents.getChunkIndex(m), -1.0))
                .reversed()
                .thenComparing(Comparator.comparingDouble(
                        (EmbeddingMatch<TextSegment> m) -> m.score()).reversed()));

        for (EmbeddingMatch<TextSegment> match : candidates) {
            Double score = scores.get(RagPipelineComponents.getChunkIndex(match));
            if (score != null && match.embedded() != null && match.embedded().metadata() != null) {
                match.embedded().metadata().put("rerankScore", score);
            }
        }

        List<EmbeddingMatch<TextSegment>> result = new ArrayList<>(candidates.size() + tail.size());
        result.addAll(candidates);
        result.addAll(tail);
        log.info("[Rerank] 完成: candidates={}, scored={}, tail={}",
                candidates.size(), scores.size(), tail.size());
        return result;
    }

    private Map<Integer, Double> score(String question, List<EmbeddingMatch<TextSegment>> candidates) {
        // 场景模型解析不出来时 buildForScene 会抛（例如 RAG_RERANK 没绑定、也没有 TOOL 兜底）。
        // 这一层是"可选增强"：解析失败必须退回向量顺序，不能让整条检索链一起失败
        // （实测 RAG_RERANK 未绑定时异常冒泡 → 上下文为空 → 每条问答都变成预写拒答）。
        dev.langchain4j.model.chat.ChatModel model;
        try {
            model = chatModelFactory.buildForScene(AiScene.RAG_RERANK);
        } catch (Exception e) {
            log.warn("[Rerank] 场景模型解析失败，跳过重排（保持向量顺序）: {}", e.getMessage());
            return Map.of();
        }
        if (model == null) {
            log.warn("[Rerank] TOOL 模型未配置，跳过重排");
            return Map.of();
        }
        Map<Integer, Double> scores = new HashMap<>();
        for (int from = 0; from < candidates.size(); from += BATCH_SIZE) {
            int to = Math.min(from + BATCH_SIZE, candidates.size());
            List<EmbeddingMatch<TextSegment>> batch = candidates.subList(from, to);
            try {
                String text = model.chat(List.of(
                        SystemMessage.from(SYSTEM_PROMPT),
                        UserMessage.from(buildUserMessage(question, batch))))
                        .aiMessage().text();
                parseScores(text, scores);
            } catch (Exception e) {
                log.warn("[Rerank] 批次 {}-{} 失败（保留向量顺序）: {}", from, to, e.getMessage());
            }
        }
        return scores;
    }

    private static String buildUserMessage(String question, List<EmbeddingMatch<TextSegment>> batch) {
        StringBuilder sb = new StringBuilder(4096);
        sb.append("【读者问题】\n").append(question).append("\n\n【候选片段】\n");
        for (EmbeddingMatch<TextSegment> match : batch) {
            String text = match.embedded() != null ? match.embedded().text() : "";
            String preview = text.length() > PREVIEW_CHARS ? text.substring(0, PREVIEW_CHARS) : text;
            sb.append('[').append(RagPipelineComponents.getChunkIndex(match)).append("] ")
              .append(preview.replace("\n", " ")).append('\n');
        }
        sb.append("\n【任务】按候选编号逐行输出 `chunkIndex:分数`，分数 0~3，不要解释、不要遗漏。");
        return sb.toString();
    }

    private static void parseScores(String text, Map<Integer, Double> scores) {
        if (text == null || text.isBlank()) {
            return;
        }
        Matcher matcher = SCORE_LINE.matcher(text);
        while (matcher.find()) {
            try {
                scores.put(Integer.parseInt(matcher.group(1)),
                        Double.parseDouble(matcher.group(2)));
            } catch (NumberFormatException ignored) {
            }
        }
    }

    private static final String SYSTEM_PROMPT = """
            你是检索结果重排器。判断每个候选片段对回答读者问题的价值：
            3 = 直接回答问题的核心内容
            2 = 重要背景或论据
            1 = 弱相关
            0 = 无关
            只输出每行 `chunkIndex:分数`，不要解释、不要遗漏任何一个候选。""";
}
