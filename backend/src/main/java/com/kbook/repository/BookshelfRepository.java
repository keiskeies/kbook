package com.kbook.repository;

import com.kbook.entity.Bookshelf;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * 书架数据访问层
 */
public interface BookshelfRepository extends BaseRepository<Bookshelf, Long> {

    /**
     * 查询用户书架中指定图书的记录
     */
    Optional<Bookshelf> findByUserIdAndBookId(Long userId, Long bookId);

    /**
     * 查询用户书架列表，按排序权重和添加时间降序排列
     */
    List<Bookshelf> findByUserIdOrderBySortOrderDescAddedAtDesc(Long userId);

    /**
     * 判断用户书架中是否包含指定图书
     */
    boolean existsByUserIdAndBookId(Long userId, Long bookId);

    /**
     * 从用户书架中移除指定图书
     */
    @Modifying
    @Transactional
    void deleteByUserIdAndBookId(Long userId, Long bookId);

    /**
     * 统计用户书架中的图书数量
     */
    long countByUserId(Long userId);

    /**
     * 查询用户书架中的图书ID列表
     */
    @Query("SELECT b.bookId FROM Bookshelf b WHERE b.userId = :userId ORDER BY b.sortOrder DESC, b.addedAt DESC")
    List<Long> findBookIdsByUserId(@Param("userId") Long userId);
}
