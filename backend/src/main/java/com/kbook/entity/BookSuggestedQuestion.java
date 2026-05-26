package com.kbook.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * 图书预设问题表 — 存储 AI 为每本书生成的推荐提问
 */
@Data
@Entity
@Table(name = "book_suggested_questions", indexes = {
        @Index(name = "idx_book_id", columnList = "book_id")
})
public class BookSuggestedQuestion {

    /** 主键 ID */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 关联图书 ID */
    @Column(name = "book_id", nullable = false)
    private Long bookId;

    /** 预设问题内容 */
    @Column(name = "question", nullable = false, length = 500)
    private String question;

    /** 创建时间 */
    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
