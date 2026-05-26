package com.kbook.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 用户书籍偏好实体
 * 记录用户不喜欢的书籍标签/类型，推荐时排除
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "user_book_preference", uniqueConstraints = {
        @UniqueConstraint(name = "uk_user_category_value", columnNames = {"user_id", "category", "value"})
})
public class UserBookPreference {

    /** 主键 ID */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 用户 ID */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /**
     * 偏好类别：TAG(标签)、AUTHOR(作者)、FORMAT(格式)
     */
    @Column(nullable = false, length = 20)
    private String category;

    /**
     * 偏好值：如 "科幻"、"金庸" 等
     */
    @Column(nullable = false, length = 100)
    private String value;

    /**
     * 偏好类型：EXCLUDE(不想看)、INCLUDE(想看)
     */
    @Column(nullable = false, length = 20)
    @Builder.Default
    private String type = "EXCLUDE";

    /** 创建时间 */
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    /** JPA 持久化前回调，自动设置创建时间 */
    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
