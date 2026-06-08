package com.kbook.entity;

import com.kbook.common.entity.IEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * JPA 实体基类 — 提供基于 ID 的 equals/hashCode 和审计字段。
 * <p>
 * 解决的问题：
 * 1. @Data 自动生成基于所有字段的 equals/hashCode，导致懒加载字段触发 N+1
 * 2. 双向关联中 @Data 的 toString 会无限递归
 * 3. 实体放入 HashSet/HashMap 时因持久化状态变化导致 hashCode 不稳定
 * 4. 所有实体重复定义 createdAt/updatedAt 字段和 JPA 回调
 * <p>
 * 子类只需继承本类，无需再写 equals/hashCode/createdAt/updatedAt。
 */
@MappedSuperclass
@Getter
@Setter
@ToString
public abstract class BaseEntity implements IEntity<Long> {

    /**
     * 创建时间
     */
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /**
     * 获取实体的业务主键。
     * 子类必须实现：返回主键字段。
     */
    public abstract Long getId();

    /**
     * JPA 持久化前回调，自动设置创建和更新时间
     */
    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    /**
     * JPA 更新前回调，自动设置更新时间
     */
    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        BaseEntity that = (BaseEntity) o;
        Long thisId = this.getId();
        Long thatId = that.getId();
        if (thisId == null || thatId == null) return false;
        return Objects.equals(thisId, thatId);
    }

    @Override
    public int hashCode() {
        Long id = this.getId();
        return id != null ? Objects.hashCode(id) : System.identityHashCode(this);
    }
}
