package com.kbook.repository;

import com.kbook.common.repository.BaseRepository;
import com.kbook.entity.Bookshelf;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * 书架数据访问层
 * <p>
 * 简单查询统一使用 BaseRepository.query() 的 Fluent API
 */
public interface BookshelfRepository extends BaseRepository<Bookshelf, Long> {

    /**
     * 查询用户书架中的图书ID列表
     */
    @Query("SELECT b.bookId FROM Bookshelf b WHERE b.userId = :userId ORDER BY b.sortOrder DESC, b.addedAt DESC")
    List<Long> findBookIdsByUserId(@Param("userId") Long userId);
}
