package com.kbook.common.entity;

import java.time.LocalDateTime;

/**
 * 实体接口 — 泛型约束，所有 JPA 实体必须实现。
 * <p>
 * 参考 talking-mouse-server 的 IEntity，精简掉部门权限等 KBook 不需要的功能。
 * 定义了所有实体共有的审计字段。
 *
 * @param <ID> 主键类型
 */
public interface IEntity<ID> {

    /**
     * 获取实体主键
     */
    ID getId();

    /**
     * 获取创建时间
     */
    LocalDateTime getCreatedAt();

    /**
     * 获取更新时间
     */
    LocalDateTime getUpdatedAt();
}
