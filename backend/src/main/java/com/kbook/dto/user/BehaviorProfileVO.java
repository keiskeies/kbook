package com.kbook.dto.user;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 行为画像视图对象（L2 画像）。
 * <p>
 * 将 {@link com.kbook.entity.UserBehaviorProfile} 中的 JSON 字段解析为结构化列表，
 * 供前端"AI 眼中的你"板块展示与编辑。
 */
@Data
public class BehaviorProfileVO {

    /** 兴趣主题（带权重） */
    private List<WeightedItem> interestTags;

    /** 阅读动机（带权重） */
    private List<WeightedItem> readingMotivations;

    /** 知识盲区（纯字符串） */
    private List<String> knowledgeGaps;

    /** 价值观倾向（纯字符串） */
    private List<String> valueOrientation;

    /** 认知深度枚举名 */
    private String cognitiveDepth;

    /** 认知深度中文展示 */
    private String cognitiveDepthLabel;

    /** 情绪基调枚举名 */
    private String emotionalTone;

    /** 情绪基调中文展示 */
    private String emotionalToneLabel;

    /** 累计抽取的提问数 */
    private Integer totalSignals;

    /** 最后一次抽取时间 */
    private LocalDateTime lastInferredAt;

    /** 最近 20 条原始提问（复盘/审计用） */
    private List<String> recentSignals;

    /** 带权重的条目 */
    @Data
    @AllArgsConstructor
    public static class WeightedItem {
        /** 标签/动机文本 */
        private String tag;
        /** 权重 0-1 */
        private Double weight;
    }
}
