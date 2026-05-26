package com.kbook.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 评论实体
 * 支持书籍评论和章节评论，支持嵌套回复
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "comments", indexes = {
        @Index(name = "idx_comment_book", columnList = "book_id"),
        @Index(name = "idx_comment_user", columnList = "user_id"),
        @Index(name = "idx_comment_parent", columnList = "parent_id"),
        @Index(name = "idx_comment_book_chapter", columnList = "book_id,chapter_id")
})
public class Comment {

    /** 主键 ID */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 评论者用户 ID */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 图书 ID */
    @Column(name = "book_id", nullable = false)
    private Long bookId;

    /** 章节 ID（null 表示书籍级别的评论） */
    @Column(name = "chapter_id", length = 200)
    private String chapterId;

    /** 父评论 ID（null 表示顶级评论） */
    @Column(name = "parent_id")
    private Long parentId;

    /** 评论内容 */
    @Column(columnDefinition = "TEXT", nullable = false)
    private String content;

    /** 点赞数（冗余计数，避免每次 COUNT） */
    @Column(name = "like_count")
    @Builder.Default
    private Integer likeCount = 0;

    /** 回复数（冗余计数） */
    @Column(name = "reply_count")
    @Builder.Default
    private Integer replyCount = 0;

    /** 收藏数（冗余计数） */
    @Column(name = "favorite_count")
    @Builder.Default
    private Integer favoriteCount = 0;

    /** 创建时间 */
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    /** 更新时间 */
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /** JPA 持久化前回调，自动设置创建和更新时间 */
    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    /** JPA 更新前回调，自动设置更新时间 */
    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
