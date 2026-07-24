package com.kbook.service.ai.behavior;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kbook.entity.UserBehaviorProfile;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 行为画像描述构建器 — 将 {@link UserBehaviorProfile} 转为供 LLM 使用的精简文本。
 *
 * <p>核心原则：长度优先级高于完整度。注入主回答 prompt 时只输出 top-3 兴趣 + top-1 动机 + 1 个认知/情绪标签，
 * 不超过 200 字。注入追问生成时输出完整画像但同样有硬上限。
 *
 * <p>不写解析失败的兜底——profile 是分析结果，schema 错误意味着 extractor 出 bug，
 * 不应该让坏数据污染 prompt，直接返回空串更安全。
 */
@Slf4j
@Component
public class BehaviorProfileBuilder {

    private final ObjectMapper objectMapper;

    public BehaviorProfileBuilder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 生成精简描述（用于图书问答主回答 / AI 助理 system prompt）。
     * 仅输出 top-3 兴趣 + top-1 动机 + 认知深度 + 情绪基调，不超过 200 字。
     */
    public String buildSummary(UserBehaviorProfile profile) {
        if (profile == null) return "";

        StringBuilder sb = new StringBuilder();
        List<WeightedTag> interests = parseWeightedTags(profile.getInterestTags());
        if (!interests.isEmpty()) {
            sb.append("近期关注：");
            interests.stream().limit(3).forEach(t -> sb.append(t.tag()).append("、"));
            deleteLast(sb, 1);
            sb.append("\n");
        }

        List<WeightedTag> motivations = parseWeightedTags(profile.getReadingMotivations());
        if (!motivations.isEmpty()) {
            sb.append("阅读动机：").append(motivations.get(0).tag()).append("\n");
        }

        if (profile.getCognitiveDepth() != null) {
            String depth = switch (profile.getCognitiveDepth()) {
                case SURFACE -> "表层";
                case ANALYTICAL -> "分析型";
                case CRITICAL -> "批判型";
            };
            sb.append("认知深度：").append(depth);
            if (profile.getCognitiveDepth() == UserBehaviorProfile.CognitiveDepth.ANALYTICAL
                    || profile.getCognitiveDepth() == UserBehaviorProfile.CognitiveDepth.CRITICAL) {
                sb.append("（可讨论理论概念）");
            }
            sb.append("\n");
        }

        if (profile.getEmotionalTone() != null) {
            String tone = switch (profile.getEmotionalTone()) {
                case SEEKING_VALIDATION -> "寻求认同";
                case EXPLORING -> "探索中";
                case QUESTIONING -> "质疑";
                case RESIGNED -> "无奈";
                case OPTIMISTIC -> "积极求变";
            };
            sb.append("情绪基调：").append(tone);
        }

        String result = sb.toString().trim();
        if (result.length() > 200) {
            result = result.substring(0, 200);
        }
        return result;
    }

    /**
     * 生成完整描述（用于追问生成，可展示更多维度但仍硬限制 ≤ 400 字）。
     */
    public String buildFull(UserBehaviorProfile profile) {
        if (profile == null) return "";

        StringBuilder sb = new StringBuilder();
        List<WeightedTag> interests = parseWeightedTags(profile.getInterestTags());
        if (!interests.isEmpty()) {
            sb.append("近期兴趣：");
            interests.forEach(t -> sb.append(t.tag()).append("(").append(formatWeight(t.weight())).append(")、"));
            deleteLast(sb, 1);
            sb.append("\n");
        }

        List<WeightedTag> motivations = parseWeightedTags(profile.getReadingMotivations());
        if (!motivations.isEmpty()) {
            sb.append("阅读动机：");
            motivations.forEach(t -> sb.append(t.tag()).append("、"));
            deleteLast(sb, 1);
            sb.append("\n");
        }

        List<String> gaps = parseStringList(profile.getKnowledgeGaps());
        if (!gaps.isEmpty()) {
            sb.append("知识盲区：");
            gaps.forEach(g -> sb.append(g).append("、"));
            deleteLast(sb, 1);
            sb.append("\n");
        }

        List<String> values = parseStringList(profile.getValueOrientation());
        if (!values.isEmpty()) {
            sb.append("价值观倾向：");
            values.forEach(v -> sb.append(v).append("、"));
            deleteLast(sb, 1);
            sb.append("\n");
        }

        if (profile.getCognitiveDepth() != null) {
            sb.append("认知深度：").append(profile.getCognitiveDepth().name()).append("\n");
        }
        if (profile.getEmotionalTone() != null) {
            sb.append("情绪基调：").append(profile.getEmotionalTone().name());
        }

        String result = sb.toString().trim();
        if (result.length() > 400) {
            result = result.substring(0, 400);
        }
        return result;
    }

    // ==================== 内部解析 ====================

    record WeightedTag(String tag, double weight) {}

    List<WeightedTag> parseWeightedTags(String json) {
        if (json == null || json.isBlank()) return Collections.emptyList();
        try {
            List<Map<String, Object>> raw = objectMapper.readValue(json, new TypeReference<>() {});
            List<WeightedTag> result = new ArrayList<>();
            for (Map<String, Object> m : raw) {
                Object tag = m.get("tag");
                if (tag == null) tag = m.get("motivation"); // readingMotivations 用 motivation 键
                if (tag == null) continue;
                double w = 0.5;
                Object wObj = m.get("weight");
                if (wObj instanceof Number n) w = n.doubleValue();
                result.add(new WeightedTag(tag.toString(), w));
            }
            result.sort((a, b) -> Double.compare(b.weight, a.weight));
            return result;
        } catch (Exception e) {
            log.warn("解析 weightedTags 失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    List<String> parseStringList(String json) {
        if (json == null || json.isBlank()) return Collections.emptyList();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("解析 stringList 失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private void deleteLast(StringBuilder sb, int n) {
        int len = sb.length();
        if (len >= n) sb.setLength(len - n);
    }

    private String formatWeight(double w) {
        return String.format("%.1f", w);
    }
}
