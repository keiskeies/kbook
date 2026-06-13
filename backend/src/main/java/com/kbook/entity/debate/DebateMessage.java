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
 * 奇葩说辩论消息实体 — 存储每个辩手的发言记录
 * <p>
 * 每轮发言包含 side 立场标识和 roundType 轮次类型，
 * 用于前端分屏渲染和评分系统。
 */
@Getter
@Setter
@ToString
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "debate_messages", indexes = {
        @Index(name = "idx_db_msg_session_id", columnList = "session_id"),
        @Index(name = "idx_db_msg_user_id", columnList = "user_id"),
        @Index(name = "idx_db_msg_role_key", columnList = "role_key"),
        @Index(name = "idx_db_msg_session_round", columnList = "session_id,round_number")
})
public class DebateMessage extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 用户 ID */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 会话 ID */
    @Column(name = "session_id", nullable = false, length = 100)
    private String sessionId;

    /** 书籍 ID */
    @Column(name = "book_id", nullable = false)
    private Long bookId;

    /** 性格键名（如 LOGICAL、SHARP、HOST） */
    @Column(name = "role_key", nullable = false, length = 30)
    private String roleKey;

    /** 性格中文名 */
    @Column(name = "role_name", nullable = false, length = 30)
    private String roleName;

    /** 位置键名（如 PRO_1、CON_2、HOST） */
    @Column(name = "position_key", length = 30)
    private String positionKey;

    /** 立场：NEUTRAL / PRO / CON */
    @Column(name = "side", nullable = false, length = 10)
    private String side;

    /** 发言内容 */
    @Column(columnDefinition = "TEXT", nullable = false)
    private String content;

    /** 轮次号 1-5 */
    @Column(name = "round_number", nullable = false)
    private Integer roundNumber;

    /** 轮次类型：OPENING / CROSS_EXAM / REBUTTAL / FREE / CLOSING */
    @Column(name = "round_type", nullable = false, length = 15)
    private String roundType;

    /** 质询角色：QUESTIONER / ANSWERER（仅 CROSS_EXAM 轮使用） */
    @Column(name = "exam_role", length = 15)
    private String examRole;

    /** 该轮发言顺序号（每个轮次内递增） */
    @Column(name = "phase_order")
    private Integer phaseOrder;

    @Override
    public Long getId() {
        return id;
    }
}
