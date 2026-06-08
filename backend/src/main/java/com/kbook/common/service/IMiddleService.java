package com.kbook.common.service;

import com.kbook.common.entity.IMiddleEntity;

import javax.annotation.Nonnull;
import java.util.Collection;
import java.util.List;

/**
 * 中间表服务接口 — 提供基于两个ID的关联操作
 * <p>
 * 参考 talking-mouse-server 的 IMiddleService，精简掉部门权限相关方法，适配 Long 主键
 *
 * @param <T>   中间实体类型
 * @param <ID1> 第一个实体的ID类型
 * @param <ID2> 第二个实体的ID类型
 */
public interface IMiddleService<T extends IMiddleEntity<ID1, ID2>, ID1, ID2>
        extends IService<T, Long> {

    /**
     * 根据ID1和ID2查找单个实体
     */
    T findOneById(@Nonnull ID1 id1, @Nonnull ID2 id2);

    /**
     * 根据ID1查找实体列表
     */
    List<T> findListById1(@Nonnull ID1 id1);

    /**
     * 根据ID1统计关联实体数量
     */
    Long countById1(@Nonnull ID1 id1);

    /**
     * 根据ID1集合查找实体列表
     */
    List<T> findListById1s(@Nonnull Collection<ID1> id1s);

    /**
     * 根据ID2查找实体列表
     */
    List<T> findListById2(@Nonnull ID2 id2);

    /**
     * 根据ID2统计关联实体数量
     */
    Long countById2(@Nonnull ID2 id2);

    /**
     * 根据ID2集合查找实体列表
     */
    List<T> findListById2s(@Nonnull Collection<ID2> id2s);

    /**
     * 根据ID1保存或更新实体列表
     */
    List<T> saveOrUpdateById1(@Nonnull List<T> ts, @Nonnull ID1 id1);

    /**
     * 根据ID1保存实体列表
     */
    List<T> saveById1(@Nonnull List<T> ts, @Nonnull ID1 id1);

    /**
     * 根据ID2保存或更新实体列表
     */
    List<T> saveOrUpdateById2(@Nonnull List<T> ts, @Nonnull ID2 id2);

    /**
     * 根据ID2保存实体列表
     */
    List<T> saveById2(@Nonnull List<T> ts, @Nonnull ID2 id2);

    /**
     * 根据ID1删除记录
     */
    void deleteById1(@Nonnull ID1 id1);

    /**
     * 根据ID2删除记录
     */
    void deleteById2(@Nonnull ID2 id2);

    /**
     * 检查指定ID组合的实体是否存在
     */
    Boolean exist(@Nonnull ID1 id1, @Nonnull ID2 id2);
}
