package com.kbook.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 圆桌派覆盖度实体 — 存储会话对图书内容的覆盖度评估数据
 * <p>
 * 每个会话一条记录，随讨论进展增量更新。
 * HOST 发言时读取此数据，精准引导未覆盖的话题。
 */
@Getter
@Setter
@ToString
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "round_table_coverages", indexes = {
        @Index(name = "idx_rt_cov_session_id", columnList = "session_id", unique = true),
        @Index(name = "idx_rt_cov_book_id", columnList = "book_id")
})
public class RoundTableCoverage extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 会话唯一标识 */
    @Column(name = "session_id", nullable = false, length = 100, unique = true)
    private String sessionId;

    /** 书籍 ID */
    @Column(name = "book_id", nullable = false)
    private Long bookId;

    // ====== 内容块覆盖度（算法1） ======

    /** 内容块总数 */
    @Column(name = "total_blocks")
    private Integer totalBlocks;

    /** 已覆盖的块数（覆盖等级 >= 1） */
    @Column(name = "covered_blocks")
    private Integer coveredBlocks;

    /** 深入讨论的块数（覆盖等级 >= 2） */
    @Column(name = "deep_blocks")
    private Integer deepBlocks;

    /** 内容块加权覆盖得分 0-100 */
    @Column(name = "block_coverage_score")
    private Double blockCoverageScore;

    /** 内容块列表 JSON（序列化 ContentBlock） */
    @Column(name = "blocks_json", columnDefinition = "TEXT")
    private String blocksJson;

    /** 逐块覆盖详情 JSON（序列化 BlockCoverageDetail） */
    @Column(name = "block_details_json", columnDefinition = "TEXT")
    private String blockDetailsJson;

    // ====== 概念标签覆盖度（算法4） ======

    /** 概念标签总数 */
    @Column(name = "total_concepts")
    private Integer totalConcepts;

    /** 已覆盖的概念数 */
    @Column(name = "covered_concepts_count")
    private Integer coveredConceptsCount;

    /** 概念标签覆盖得分 0-100 */
    @Column(name = "concept_coverage_score")
    private Double conceptCoverageScore;

    /** 已覆盖的概念标签 JSON 数组 */
    @Column(name = "covered_concepts_json", columnDefinition = "TEXT")
    private String coveredConceptsJson;

    /** 未覆盖的概念标签 JSON 数组 */
    @Column(name = "missed_concepts_json", columnDefinition = "TEXT")
    private String missedConceptsJson;

    // ====== LLM 综合评估（算法3） ======

    /** LLM 6维度评分 JSON: {"广度覆盖":6.0,"深度挖掘":8.0,...} */
    @Column(name = "llm_dimensions_json", columnDefinition = "TEXT")
    private String llmDimensionsJson;

    /** LLM 评估强项 JSON 数组 */
    @Column(name = "llm_strengths_json", columnDefinition = "TEXT")
    private String llmStrengthsJson;

    /** LLM 评估不足 JSON 数组 */
    @Column(name = "llm_weaknesses_json", columnDefinition = "TEXT")
    private String llmWeaknessesJson;

    /** LLM 改进建议 JSON 数组 */
    @Column(name = "llm_suggestions_json", columnDefinition = "TEXT")
    private String llmSuggestionsJson;

    /** LLM 综合评估得分 0-100 */
    @Column(name = "llm_assessment_score")
    private Double llmAssessmentScore;

    // ====== 综合评分 ======

    /** 综合覆盖度得分 0-100 */
    @Column(name = "overall_score")
    private Double overallScore;

    /** 等级（如 S/A/B/C/D/F） */
    @Column(length = 20)
    private String grade;

    /** 图书内容分块总数（来自 Qdrant） */
    @Column(name = "total_chunks")
    private Integer totalChunks;

    /** 上次计算时已处理的消息数（用于增量更新） */
    @Column(name = "processed_message_count")
    @Builder.Default
    private Integer processedMessageCount = 0;

    @Override
    public Long getId() {
        return id;
    }
}
