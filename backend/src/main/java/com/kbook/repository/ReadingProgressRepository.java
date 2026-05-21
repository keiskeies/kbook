package com.kbook.repository;

import com.kbook.entity.ReadingProgress;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * 阅读进度数据访问层
 */
public interface ReadingProgressRepository extends JpaRepository<ReadingProgress, Long> {

    Optional<ReadingProgress> findByUserIdAndBookId(Long userId, Long bookId);

    List<ReadingProgress> findByUserIdOrderByUpdatedAtDesc(Long userId);

    Page<ReadingProgress> findByUserIdOrderByUpdatedAtDesc(Long userId, Pageable pageable);

    void deleteByUserIdAndBookId(Long userId, Long bookId);

    /**
     * 批量获取用户指定图书的进度
     */
    @Query("SELECT rp FROM ReadingProgress rp WHERE rp.userId = :userId AND rp.bookId IN :bookIds")
    List<ReadingProgress> findByUserIdAndBookIdIn(@Param("userId") Long userId,
                                                    @Param("bookIds") List<Long> bookIds);

    /**
     * 获取用户最近阅读的进度（带数量限制，包括已读完的）
     */
    @Query("SELECT rp FROM ReadingProgress rp WHERE rp.userId = :userId ORDER BY rp.updatedAt DESC")
    List<ReadingProgress> findRecentReading(@Param("userId") Long userId, Pageable pageable);

    /**
     * 统计用户已读完成的数量
     */
    @Query("SELECT COUNT(rp) FROM ReadingProgress rp WHERE rp.userId = :userId AND rp.progress >= 1.0")
    long countCompletedByUserId(@Param("userId") Long userId);

    /**
     * 获取用户已读完的图书ID列表
     */
    @Query("SELECT rp.bookId FROM ReadingProgress rp WHERE rp.userId = :userId AND rp.progress >= 1.0")
    List<Long> findCompletedBookIdsByUserId(@Param("userId") Long userId);

    /**
     * 获取用户所有有进度的图书ID列表
     */
    @Query("SELECT rp.bookId FROM ReadingProgress rp WHERE rp.userId = :userId")
    List<Long> findAllBookIdsByUserId(@Param("userId") Long userId);

    /**
     * 获取用户已读完的所有记录
     */
    @Query("SELECT rp FROM ReadingProgress rp WHERE rp.userId = :userId AND rp.progress >= 1.0 ORDER BY rp.updatedAt DESC")
    List<ReadingProgress> findCompletedByUserId(@Param("userId") Long userId);
}
