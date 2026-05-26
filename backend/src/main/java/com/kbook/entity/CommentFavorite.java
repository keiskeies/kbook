package com.kbook.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 评论收藏实体
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "comment_favorites", uniqueConstraints = {
        @UniqueConstraint(name = "uk_comment_favorite", columnNames = {"comment_id", "user_id"})
})
public class CommentFavorite {

    /** 主键 ID */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 评论 ID */
    @Column(name = "comment_id", nullable = false)
    private Long commentId;

    /** 收藏用户 ID */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 创建时间 */
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    /** JPA 持久化前回调，自动设置创建时间 */
    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
