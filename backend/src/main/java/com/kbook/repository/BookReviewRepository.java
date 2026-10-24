package com.kbook.repository;

import com.kbook.common.repository.BaseRepository;
import com.kbook.entity.BookReview;

/**
 * 书评数据访问层
 * <p>
 * 分页与排序统一走 BaseRepository.query() 的 Fluent API
 */
public interface BookReviewRepository extends BaseRepository<BookReview, Long> {

    long countByBookId(Long bookId);

    long countByUserId(Long userId);
}
