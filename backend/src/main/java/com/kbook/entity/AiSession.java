package com.kbook.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * AI 会话实体
 * 管理用户与 AI 的多轮对话会话，每个会话包含多条 AiConversation 记录
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "ai_sessions", indexes = {
        @Index(name = "idx_session_user_id", columnList = "user_id"),
        @Index(name = "idx_session_type", columnList = "type"),
        @Index(name = "idx_session_book_id", columnList = "book_id"),
        @Index(name = "idx_session_user_type", columnList = "user_id, type")
})
public class AiSession {

    /** 主键 ID */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 用户 ID */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 会话类型：CHAT(通用对话) / BOOK(图书问答) / LIBRARIAN(图书管理员) */
    @Column(nullable = false, length = 20)
    private String type;

    /** 关联图书 ID（图书问答时非空） */
    @Column(name = "book_id")
    private Long bookId;

    /** 会话唯一标识（UUID，用于关联 AiConversation） */
    @Column(name = "session_id", nullable = false, length = 100, unique = true)
    private String sessionId;

    /** 会话标题（取首条用户消息摘要） */
    @Column(length = 200)
    private String title;

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
