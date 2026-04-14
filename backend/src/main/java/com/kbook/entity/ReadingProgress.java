package com.kbook.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 阅读进度实体
 * 进度计算规则：
 * - EPUB/TXT: progress = 已读字符数 / 总字符数 (0.0~1.0)
 * - PDF: progress = 当前页码 / 总页数 (0.0~1.0)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "reading_progress", uniqueConstraints = {
        @UniqueConstraint(name = "uk_user_book", columnNames = {"user_id", "book_id"})
})
public class ReadingProgress {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "book_id", nullable = false)
    private Long bookId;

    /** 进度百分比 (0.0 ~ 1.0) */
    @Column(nullable = false)
    @Builder.Default
    private Double progress = 0.0;

    /** 当前位置（EPUB: chapterId, TXT: charOffset, PDF: pageNumber） */
    @Column(name = "current_position", length = 100)
    private String currentPosition;

    /** 更新时间戳（用于冲突解决：时间戳覆盖策略） */
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /** 用户评分（1-5星整数），null 表示未评分 */
    @Column(name = "user_rating")
    private Integer userRating;

    @PrePersist
    protected void onCreate() {
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
