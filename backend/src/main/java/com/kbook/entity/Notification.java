package com.kbook.entity;

import com.kbook.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.NoArgsConstructor;

/**
 * 系统通知实体
 * 类型：COMMENT_REPLY(评论被回复) / COMMENT_LIKED(评论被点赞) / COMMENT_FAVORITED(评论被收藏) / NEW_REVIEW(关注的人发书评)
 */
@Getter
@Setter
@ToString
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "notifications", indexes = {
        @Index(name = "idx_notif_user", columnList = "receiver_id"),
        @Index(name = "idx_notif_user_read", columnList = "receiver_id, is_read")
})
public class Notification extends BaseEntity {

    /** 主键 ID */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 接收通知的用户 ID */
    @Column(name = "receiver_id", nullable = false)
    private Long receiverId;

    /** 触发通知的用户 ID（系统通知时为 null） */
    @Column(name = "trigger_user_id")
    private Long triggerUserId;

    /** 通知类型：COMMENT_REPLY / COMMENT_LIKED / COMMENT_FAVORITED / NEW_REVIEW / ROUND_TABLE_REPORT */
    @Column(name = "type", nullable = false, length = 30)
    private String type;

    /** 关联的评论 ID */
    @Column(name = "comment_id")
    private Long commentId;

    /** 关联的图书 ID */
    @Column(name = "book_id")
    private Long bookId;

    /** 关联的圆桌派会话 ID（ROUND_TABLE_REPORT 类型使用） */
    @Column(name = "session_id", length = 100)
    private String sessionId;

    /** 是否已读 */
    @Column(name = "is_read", nullable = false)
    @Builder.Default
    private Boolean isRead = false;

    @Override
    public Long getId() {
        return id;
    }
}
