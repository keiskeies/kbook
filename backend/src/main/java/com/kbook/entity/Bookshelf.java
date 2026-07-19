package com.kbook.entity;

import com.kbook.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 用户书架实体
 */
@Getter
@Setter
@ToString
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "bookshelf", uniqueConstraints = {
        @UniqueConstraint(name = "uk_user_book_shelf", columnNames = {"user_id", "book_id"})
})
public class Bookshelf extends BaseEntity {

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

    /** 排序权重（越大越靠前） */
    @Column(name = "sort_order")
    @Builder.Default
    private Integer sortOrder = 0;

    /** 加入书架时间 */
    @Column(name = "added_at", updatable = false)
    private LocalDateTime addedAt;

    /** JPA 持久化前回调，自动设置加入时间并触发父类审计字段初始化 */
    @PrePersist
    protected void onCreate() {
        super.onCreate();
        addedAt = LocalDateTime.now();
    }

    @Override
    public Long getId() {
        return id;
    }
}
