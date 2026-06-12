package com.kbook.entity;

import com.kbook.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 圆桌派解读报告实体 — 存储 LLM 生成的深度解读报告
 * <p>
 * 与 RoundTableSession 的 sessionId 绑定，一个会话最多一份报告。
 * 生成过程异步执行，通过状态字段追踪进度。
 */
@Getter
@Setter
@ToString
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "round_table_reports", indexes = {
        @Index(name = "idx_rt_report_session_id", columnList = "session_id", unique = true),
        @Index(name = "idx_rt_report_user_id", columnList = "user_id")
})
public class RoundTableReport extends BaseEntity {

    /** 主键 ID */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 会话唯一标识（关联 round_table_sessions.session_id） */
    @Column(name = "session_id", nullable = false, length = 100, unique = true)
    private String sessionId;

    /** 用户 ID */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 书籍 ID */
    @Column(name = "book_id", nullable = false)
    private Long bookId;

    /** 报告内容（Markdown 格式） */
    @Column(columnDefinition = "TEXT")
    private String content;

    /** 报告状态：PENDING / GENERATING / COMPLETED / FAILED */
    @Column(length = 20)
    @Builder.Default
    private String status = "PENDING";

    /** 失败原因（status=FAILED 时记录） */
    @Column(name = "error_message", length = 500)
    private String errorMessage;

    @Override
    public Long getId() {
        return id;
    }
}
