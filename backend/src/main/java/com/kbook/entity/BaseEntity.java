package com.kbook.entity;

import java.util.Objects;

/**
 * JPA 实体基类 — 提供基于 ID 的 equals/hashCode。
 * <p>
 * 解决的问题：
 * 1. @Data 自动生成基于所有字段的 equals/hashCode，导致懒加载字段触发 N+1
 * 2. 双向关联中 @Data 的 toString 会无限递归
 * 3. 实体放入 HashSet/HashMap 时因持久化状态变化导致 hashCode 不稳定
 * <p>
 * 子类只需继承本类，无需再写 equals/hashCode。
 */
public abstract class BaseEntity {

    /**
     * 获取实体的业务主键。
     * 子类必须实现：返回主键字段。
     */
    public abstract Long getId();

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
