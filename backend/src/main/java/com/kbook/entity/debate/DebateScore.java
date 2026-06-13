package com.kbook.entity.debate;

import com.kbook.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 奇葩说辩论评分实体 — 存储每次发言的7维度评分
 * <p>
 * 评分由 LLM 异步生成，不阻塞发言 SSE 流程。
 * 评分范围为 1-10 分，0.5 分精度。
 */
@Getter
@Setter
@ToString
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "debate_scores", indexes = {
        @Index(name = "idx_db_score_session_id", columnList = "session_id"),
        @Index(name = "idx_db_score_message_id", columnList = "message_id"),
        @Index(name = "idx_db_score_role_key", columnList = "role_key"),
        @Index(name = "idx_db_score_session_round", columnList = "session_id,round_number")
})
public class DebateScore extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 用户 ID */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 会话 ID */
    @Column(name = "session_id", nullable = false, length = 100)
    private String sessionId;

    /** 消息 ID（关联 debate_messages） */
    @Column(name = "message_id", nullable = false)
    private Long messageId;

    /** 角色键名 */
    @Column(name = "role_key", nullable = false, length = 30)
    private String roleKey;

    /** 立场 */
    @Column(name = "side", nullable = false, length = 10)
    private String side;

    /** 轮次号 */
    @Column(name = "round_number", nullable = false)
    private Integer roundNumber;

    /** 轮次类型 */
    @Column(name = "round_type", length = 10)
    private String roundType;

    /** 逻辑性 1-10 */
    @Column(name = "logic_score")
    private Double logicScore;

    /** 论据丰富度 1-10 */
    @Column(name = "evidence_score")
    private Double evidenceScore;

    /** 反驳力 1-10 */
    @Column(name = "rebuttal_score")
    private Double rebuttalScore;

    /** 感染力 1-10 */
    @Column(name = "impact_score")
    private Double impactScore;

    /** 幽默感 1-10 */
    @Column(name = "humor_score")
    private Double humorScore;

    /** 表达清晰度 1-10 */
    @Column(name = "clarity_score")
    private Double clarityScore;

    /** 观点新颖度 1-10 */
    @Column(name = "novelty_score")
    private Double noveltyScore;

    /** 7维平均分 */
    @Column(name = "average_score")
    private Double averageScore;

    @Override
    public Long getId() {
        return id;
    }
}
