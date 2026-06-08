package com.kbook.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.NoArgsConstructor;

/**
 * 推荐反馈事件实体
 * 记录用户对推荐结果的行为反馈，用于自动调参
 * <p>
 * 反馈类型：
 * - IMPRESSION：推荐曝光（用户看到了推荐卡片）
 * - CLICK：用户点击了推荐的书
 * - FAVORITE：用户将推荐的书加入书架
 * - READ：用户开始阅读推荐的书
 * - COMPLETE：用户读完了推荐的书
 * - RATE：用户对推荐的书评分
 * - DISMISS：用户明确关闭/跳过推荐
 * <p>
 * 反馈强度（用于梯度调参）：
 * - IMPRESSION: 0.0（中性，仅用于统计曝光率）
 * - CLICK: +0.1
 * - FAVORITE: +0.3
 * - READ: +0.2
 * - COMPLETE: +0.5
 * - RATE: +rating * 0.1（评分越高正反馈越强）
 * - DISMISS: -0.2
 */
@Getter
@Setter
@ToString
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "recommend_feedback_event", indexes = {
        @Index(name = "idx_user_id", columnList = "user_id"),
        @Index(name = "idx_book_id", columnList = "book_id"),
        @Index(name = "idx_created_at", columnList = "created_at"),
        @Index(name = "idx_feedback_type", columnList = "feedback_type")
})
public class RecommendFeedbackEvent extends BaseEntity {

    /** 主键 ID */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 用户 ID */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 图书 ID */
    @Column(name = "book_id", nullable = false)
    private Long bookId;

    /** 反馈类型 */
    @Column(name = "feedback_type", nullable = false, length = 20)
    private String feedbackType;

    /** 反馈强度（正数=正反馈，负数=负反馈） */
    @Column(nullable = false)
    @Builder.Default
    private Double strength = 0.0;

    /** 哪路召回命中了这本书（RULE/VECTOR/COLLAB/EXPLORE，可多个用逗号分隔） */
    @Column(name = "recall_paths", length = 100)
    private String recallPaths;

    /** 推荐时的融合得分快照 */
    @Column(name = "recommend_score")
    private Double recommendScore;

    /** 推荐时的质量因子快照 */
    @Column(name = "quality_factor")
    private Double qualityFactor;

    /** 反馈详情（如评分值 "4"、阅读进度 "0.85" 等） */
    @Column(name = "feedback_detail", length = 100)
    private String feedbackDetail;

    @Override
    public Long getId() {
        return id;
    }
}
