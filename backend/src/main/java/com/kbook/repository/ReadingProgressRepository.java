package com.kbook.repository;

import com.kbook.common.repository.BaseRepository;
import com.kbook.entity.ReadingProgress;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * 阅读进度数据访问层
 * <p>
 * 简单查询统一使用 BaseRepository.query() 的 Fluent API
 */
public interface ReadingProgressRepository extends BaseRepository<ReadingProgress, Long> {

    /**
     * 统计用户已读完成的数量（无法用 QueryBuilder 替代聚合查询）
     */
    @Query("SELECT COUNT(rp) FROM ReadingProgress rp WHERE rp.userId = :userId AND rp.progress >= 1.0")
    long countCompletedByUserId(@Param("userId") Long userId);

    /**
     * 获取用户已读完的图书ID列表（无法用 QueryBuilder 替代字段投影）
     */
    @Query("SELECT rp.bookId FROM ReadingProgress rp WHERE rp.userId = :userId AND rp.progress >= 1.0")
    List<Long> findCompletedBookIdsByUserId(@Param("userId") Long userId);

    /**
     * 获取用户所有有进度的图书ID列表（无法用 QueryBuilder 替代字段投影）
     */
    @Query("SELECT rp.bookId FROM ReadingProgress rp WHERE rp.userId = :userId")
    List<Long> findAllBookIdsByUserId(@Param("userId") Long userId);
}
