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
 * 奇葩说辩论会话实体 — 管理 AI 辩论的会话
 * <p>
 * 每场辩论包含4轮标准赛程：开篇立论 → 奇袭攻辩 → 自由辩论 → 总结陈词。
 * 会话状态流转：ACTIVE → COMPLETED / ABANDONED
 */
@Getter
@Setter
@ToString
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "debate_sessions", indexes = {
        @Index(name = "idx_debate_session_user_id", columnList = "user_id"),
        @Index(name = "idx_debate_session_book_id", columnList = "book_id"),
        @Index(name = "idx_debate_session_session_id", columnList = "session_id")
})
public class DebateSession extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 用户 ID */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 书籍 ID */
    @Column(name = "book_id", nullable = false)
    private Long bookId;

    /** 会话唯一标识（UUID，如 db-xxx-uuid） */
    @Column(name = "session_id", nullable = false, length = 100, unique = true)
    private String sessionId;

    /** 辩题 */
    @Column(name = "topic", nullable = false, length = 500)
    private String topic;

    /** 辩题来源：LLM / USER */
    @Column(name = "topic_source", length = 10)
    @Builder.Default
    private String topicSource = "LLM";

    /** 辩题相关的书籍上下文摘要 */
    @Column(name = "book_context", columnDefinition = "TEXT")
    private String bookContext;

    /** 正方辩手角色键名列表（逗号分隔，如 PRO_1,PRO_2,PRO_3,PRO_4） */
    @Column(name = "pro_role_keys", length = 100)
    private String proRoleKeys;

    /** 反方辩手角色键名列表（逗号分隔，如 CON_1,CON_2,CON_3,CON_4） */
    @Column(name = "con_role_keys", length = 100)
    private String conRoleKeys;

    /** 当前轮次 1-4 */
    @Column(name = "current_round")
    @Builder.Default
    private Integer currentRound = 1;

    /** 当前阶段：OPENING / ATTACK / FREE / CLOSING */
    @Column(name = "current_phase", length = 20)
    @Builder.Default
    private String currentPhase = "OPENING";

    /** 会话状态：ACTIVE / COMPLETED / ABANDONED */
    @Column(length = 20)
    @Builder.Default
    private String status = "ACTIVE";

    /** 可见性：PUBLIC / PRIVATE */
    @Column(name = "visibility", length = 20)
    @Builder.Default
    private String visibility = "PRIVATE";

    @Override
    public Long getId() {
        return id;
    }
}
