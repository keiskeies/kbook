package com.kbook.common.entity;

/**
 * 中间实体接口 — 用于两个实体之间的关联关系
 * <p>
 * 参考 talking-mouse-server 的 IMiddleEntity，适配 KBook 使用 Long 类型主键
 *
 * @param <ID1> 第一个实体的ID类型
 * @param <ID2> 第二个实体的ID类型
 */
public interface IMiddleEntity<ID1, ID2> extends IEntity<Long> {

    /**
     * 获取第一个实体的ID
     */
    ID1 getId1();

    /**
     * 设置第一个实体的ID
     */
    void setId1(ID1 id1);

    /**
     * 获取第二个实体的ID
     */
    ID2 getId2();

    /**
     * 设置第二个实体的ID
     */
    void setId2(ID2 id2);
}
