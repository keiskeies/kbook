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
 * 图书问答回答反馈（方案 P2-5）。
 * <p>
 * 一条 assistant 回答 + 一个用户 = 唯一一行；重复点击是「改反馈」而不是新增行。
 * 差评自动进入待标注队列（{@code reviewStatus=QUEUED}），标注后进黄金集，
 * 与 {@code docs/eval/golden-set.real.jsonl} 形成生产回流闭环。
 * <p>
 * {@code contextMode} 由检索快照的 source 反推（small/medium/large），用于按档位分组看差评率。
 */
@Getter
@Setter
@ToString
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "answer_feedback",
        uniqueConstraints = @UniqueConstraint(name = "uk_answer_feedback_conv_user",
                columnNames = {"conversation_id", "user_id"}),
        indexes = {
                @Index(name = "idx_answer_feedback_book", columnList = "book_id"),
                @Index(name = "idx_answer_feedback_user", columnList = "user_id"),
                @Index(name = "idx_answer_feedback_queue", columnList = "review_status, created_at")
        })
public class AnswerFeedback extends BaseEntity {

    public static final String RATING_UP = "UP";
    public static final String RATING_DOWN = "DOWN";

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_QUEUED = "QUEUED";
    public static final String STATUS_ANNOTATED = "ANNOTATED";
    public static final String STATUS_DISMISSED = "DISMISSED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "book_id", nullable = false)
    private Long bookId;

    /** 对应 ai_conversations 的 assistant 行 */
    @Column(name = "conversation_id", nullable = false)
    private Long conversationId;

    @Column(name = "session_id", length = 100)
    private String sessionId;

    /** UP / DOWN */
    @Column(name = "rating", nullable = false, length = 10)
    private String rating;

    /** WRONG / NOT_GROUNDED / OFF_TOPIC / TOO_LONG / OTHER */
    @Column(name = "reason", length = 40)
    private String reason;

    @Column(name = "comment", length = 500)
    private String comment;

    /** small / medium / large，来自本轮检索快照的 source */
    @Column(name = "context_mode", length = 16)
    private String contextMode;

    @Column(name = "review_status", nullable = false, length = 16)
    @Builder.Default
    private String reviewStatus = STATUS_PENDING;

    @Column(name = "review_note", length = 500)
    private String reviewNote;

    @Override
    public Long getId() {
        return id;
    }
}
