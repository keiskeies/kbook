package com.kbook.repository;

import com.kbook.entity.Bookshelf;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * 书架数据访问层
 */
public interface BookshelfRepository extends JpaRepository<Bookshelf, Long> {

    Optional<Bookshelf> findByUserIdAndBookId(Long userId, Long bookId);

    List<Bookshelf> findByUserIdOrderBySortOrderDescAddedAtDesc(Long userId);

    boolean existsByUserIdAndBookId(Long userId, Long bookId);

    @Modifying
    void deleteByUserIdAndBookId(Long userId, Long bookId);

    long countByUserId(Long userId);

    /**
     * 查询用户书架中的图书ID列表
     */
    @Query("SELECT b.bookId FROM Bookshelf b WHERE b.userId = :userId ORDER BY b.sortOrder DESC, b.addedAt DESC")
    List<Long> findBookIdsByUserId(@Param("userId") Long userId);
}
