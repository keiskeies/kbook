package com.kbook.repository;

import com.kbook.entity.BaseEntity;
import org.springframework.data.repository.NoRepositoryBean;

import java.util.Collection;
import java.util.List;

/**
 * 中间表数据访问层接口
 * <p>
 * 替代旧的 AbstractMiddleServiceImpl，处理两个实体之间的关联关系。
 * 每个具体的中间表 Repository 需要定义自己的派生查询方法。
 *
 * @param <T>   中间实体类型，必须继承 BaseEntity
 * @param <ID1> 第一个实体的ID类型
 * @param <ID2> 第二个实体的ID类型
 */
@NoRepositoryBean
public interface MiddleRepository<T extends BaseEntity, ID1 extends Long, ID2 extends Long>
        extends BaseRepository<T, Long> {

    /**
     * 根据 ID1 和 ID2 查找单个实体
     * 子类需要实现具体的查询方法，如 findByUserIdAndBookId
     */
    T findOneByIds(ID1 id1, ID2 id2);

    /**
     * 根据 ID1 查找实体列表
     * 子类需要实现具体的查询方法，如 findByUserId
     */
    List<T> findListById1(ID1 id1);

    /**
     * 根据 ID1 统计关联实体数量
     * 子类需要实现具体的查询方法，如 countByUserId
     */
    Long countById1(ID1 id1);

    /**
     * 根据 ID1 集合查找实体列表
     */
    List<T> findListById1s(Collection<ID1> id1s);

    /**
     * 根据 ID2 查找实体列表
     * 子类需要实现具体的查询方法，如 findByBookId
     */
    List<T> findListById2(ID2 id2);

    /**
     * 根据 ID2 统计关联实体数量
     * 子类需要实现具体的查询方法，如 countByBookId
     */
    Long countById2(ID2 id2);

    /**
     * 根据 ID2 集合查找实体列表
     */
    List<T> findListById2s(Collection<ID2> id2s);

    /**
     * 根据 ID1 删除记录
     */
    void deleteById1(ID1 id1);

    /**
     * 根据 ID2 删除记录
     */
    void deleteById2(ID2 id2);

    /**
     * 检查指定 ID 组合的实体是否存在
     */
    boolean existsByIds(ID1 id1, ID2 id2);

    /**
     * 批量保存实体列表（过滤已存在的）
     */
    default List<T> saveListFilterExists(List<T> ts) {
        if (ts == null || ts.isEmpty()) {
            return List.of();
        }
        List<T> newTs = ts.stream()
                .filter(t -> !existsByIds(getId1(t), getId2(t)))
                .toList();
        if (newTs.isEmpty()) {
            return ts;
        }
        return saveAll(newTs);
    }

    /**
     * 获取实体的第一个ID（需要子类实现或通过反射获取）
     */
    default ID1 getId1(T entity) {
        throw new UnsupportedOperationException("需要实现 getId1 方法或使用反射获取");
    }

    /**
     * 获取实体的第二个ID（需要子类实现或通过反射获取）
     */
    default ID2 getId2(T entity) {
        throw new UnsupportedOperationException("需要实现 getId2 方法或使用反射获取");
    }
}