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
 * 提供 Fluent API 查询构建器：
 * <pre>
 * // 查询
 * bookRepository.query().where(Book::getStatus, eq("ACTIVE")).list();
 *
 * // 删除
 * bookRepository.delete().where(Book::getStatus, eq("DELETED")).execute();
 *
 * // 更新
 * bookRepository.update(e -> e.set(Book::setStatus, "ACTIVE")).where(Book::getId, eq(1L)).execute();
 *
 * // 统计
 * long count = bookRepository.query().where(Book::getStatus, eq("DELETED")).value();
 *
 * // 判断存在
 * boolean exists = bookRepository.query().where(Book::getEmail, eq("test@example.com")).exists();
 * </pre>
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

    // ==================== Fluent API 入口 ====================

    /**
     * 创建查询构建器
     */
    default QueryBuilder<T> query() {
        return new QueryBuilder<>(this, this);
    }

    /**
     * 创建删除构建器
     */
    default QueryBuilder<T> delete() {
        return new QueryBuilder<>(this, this);
    }

    /**
     * 创建更新构建器
     */
    default QueryBuilder<T> update() {
        return new QueryBuilder<>(this, this);
    }


}
