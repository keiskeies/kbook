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
 * 奇葩说辩论报告实体 — 存储 LLM 生成的完整辩论报告
 * <p>
 * 与 DebateSession 的 sessionId 绑定，一场辩论最多一份报告。
 * 所有轮次完成后自动触发异步生成，也可由用户手动触发。
 */
@Getter
@Setter
@ToString
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "debate_reports", indexes = {
        @Index(name = "idx_db_report_session_id", columnList = "session_id", unique = true),
        @Index(name = "idx_db_report_user_id", columnList = "user_id"),
        @Index(name = "idx_db_report_book_id", columnList = "book_id")
})
public class DebateReport extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 会话唯一标识（关联 debate_sessions.session_id） */
    @Column(name = "session_id", nullable = false, length = 100, unique = true)
    private String sessionId;

    /** 用户 ID */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 书籍 ID */
    @Column(name = "book_id", nullable = false)
    private Long bookId;

    /** 辩题 */
    @Column(name = "topic", length = 500)
    private String topic;

    /** 报告内容（Markdown 格式） */
    @Column(columnDefinition = "TEXT")
    private String content;

    /** 评分汇总 JSON */
    @Column(name = "summary_json", columnDefinition = "TEXT")
    private String summaryJson;

    /** 最佳辩手 roleKey */
    @Column(name = "best_debater", length = 30)
    private String bestDebater;

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
