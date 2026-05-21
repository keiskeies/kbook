package com.kbook.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * AI 对话记录实体
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "ai_conversations", indexes = {
        @Index(name = "idx_conv_user_id", columnList = "user_id"),
        @Index(name = "idx_conv_session_id", columnList = "session_id"),
        @Index(name = "idx_conv_type", columnList = "type"),
        @Index(name = "idx_conv_book_id", columnList = "book_id")
})
public class AiConversation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 用户 ID
     */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /**
     * 会话 ID（一次多轮对话共享）
     */
    @Column(name = "session_id", nullable = false, length = 100)
    private String sessionId;

    @Column(length = 20)
    private String type;

    @Column(name = "book_id")
    private Long bookId;

    /**
     * 角色：user / assistant / system / tool
     */
    @Column(nullable = false, length = 20)
    private String role;

    /**
     * 消息内容
     */
    @Column(columnDefinition = "TEXT", nullable = false)
    private String content;

    @Column(name = "thinking_content", columnDefinition = "TEXT")
    private String thinkingContent;

    @Column(name = "follow_up_questions", columnDefinition = "TEXT")
    private String followUpQuestions;

    /**
     * 工具调用 ID（当 role=tool 时记录）
     */
    @Column(name = "tool_call_id", length = 100)
    private String toolCallId;

    /**
     * 工具名称（当 role=tool 时记录）
     */
    @Column(name = "tool_name", length = 100)
    private String toolName;

    /**
     * Token 消耗
     */
    @Column(name = "token_count")
    private Integer tokenCount;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
