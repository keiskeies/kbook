package com.kbook.common.repository;

import com.kbook.common.util.QueryBuilder;
import com.kbook.common.entity.BaseEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.repository.NoRepositoryBean;

import java.util.Collection;
import java.util.List;

/**
 * 基础数据访问层接口
 * <p>
 * 提供便捷的 CRUD 方法和 Fluent API 查询构建器。
 *
 * @param <T>  实体类型，必须继承 BaseEntity
 * @param <ID> 主键类型
 */
@NoRepositoryBean
public interface BaseRepository<T extends BaseEntity, ID extends Long>
        extends JpaRepository<T, ID>, JpaSpecificationExecutor<T> {

    /**
     * 根据 ID 查询单个实体
     */
    default T findOneById(ID id) {
        return findById(id).orElse(null);
    }

    /**
     * 根据 ID 集合批量查询
     */
    default List<T> findListByIds(Collection<ID> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return findAllById(ids);
    }

    /**
     * 根据 ID 集合批量删除
     */
    default void deleteListByIds(Collection<ID> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        deleteAllByIdInBatch(ids);
    }

    /**
     * 创建 Fluent API 查询构建器
     * <p>
     * 使用示例：
     * <pre>
     * List&lt;Book&gt; books = bookRepository.query()
     *     .where("sessionId", eq(sessionId))
     *     .and("title", like("%关键词%"))
     *     .orderByDesc("createdAt")
     *     .list();
     * </pre>
     */
    default QueryBuilder<T> query() {
        return new QueryBuilder<>(this);
    }
}
