package com.kbook.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 用户阅读历史实体
 * 记录所有阅读行为（包括书架外的），用于协同过滤和推荐计算
 */
@Getter
@Setter
@ToString
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "user_read_history", uniqueConstraints = {
        @UniqueConstraint(name = "uk_user_book_action", columnNames = {"user_id", "book_id", "action"})
})
public class UserReadHistory extends BaseEntity {

    /** 主键 ID */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 用户 ID */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 图书 ID */
    @Column(name = "book_id", nullable = false)
    private Long bookId;

    /** 行为类型：READ(阅读), FAVORITE(收藏), RATE(评分), COMPLETE(读完) */
    @Column(nullable = false, length = 20)
    private String action;

    /** 行为权重（READ=1, FAVORITE=3, RATE=2, COMPLETE=5），用于协同过滤 */
    @Column(nullable = false)
    @Builder.Default
    private Integer weight = 1;

    /** 行为详情（如评分值 "4"、进度 "0.85" 等） */
    @Column(name = "action_detail", length = 50)
    private String actionDetail;

    /** 创建时间 */
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    /** JPA 持久化前回调，自动设置创建时间 */
    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    @Override
    public Long getId() {
        return id;
    }
}
