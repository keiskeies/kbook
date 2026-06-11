package com.kbook.repository;

import com.kbook.entity.BaseEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.repository.NoRepositoryBean;

import java.util.Collection;
import java.util.List;

/**
 * 基础数据访问层接口
 * <p>
 * 替代旧的 AbstractServiceImpl，将通用 CRUD 方法下沉到 Repository 层。
 * 使用 Spring Data JPA 的 default 方法特性，避免继承链过长。
 * <p>
 * 注意：JpaRepository 已经提供 findAll(Pageable)、deleteById、existsById、save、saveAll 等，
 * 此处仅添加额外的便利方法（findOneById、findListByIds、deleteListByIds），
 * 不重复声明 JpaRepository 已有的非 default 方法，否则 Spring Data 会尝试将其解析为派生查询。
 *
 * @param <T>  实体类型，必须继承 BaseEntity
 * @param <ID> 主键类型
 */
@NoRepositoryBean
public interface BaseRepository<T extends BaseEntity, ID extends Long>
        extends JpaRepository<T, ID>, JpaSpecificationExecutor<T> {

    /**
     * 根据 ID 查询单个实体
     *
     * @param id 主键ID
     * @return 实体对象，如果不存在返回 null
     */
    default T findOneById(ID id) {
        return findById(id).orElse(null);
    }

    /**
     * 根据 ID 集合批量查询
     *
     * @param ids ID 集合
     * @return 实体列表，如果集合为空返回空列表
     */
    default List<T> findListByIds(Collection<ID> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return findAllById(ids);
    }

    /**
     * 根据 ID 集合批量删除
     *
     * @param ids ID 集合
     */
    default void deleteListByIds(Collection<ID> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        deleteAllByIdInBatch(ids);
    }
}