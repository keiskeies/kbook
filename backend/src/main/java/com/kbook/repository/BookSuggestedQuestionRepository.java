package com.kbook.repository;

import com.kbook.common.repository.BaseRepository;
import com.kbook.entity.BookSuggestedQuestion;
import org.springframework.stereotype.Repository;

/**
 * 图书预设问题数据访问层
 * <p>
 * 查询统一使用 BaseRepository.query() 的 Fluent API
 */
@Repository
public interface BookSuggestedQuestionRepository extends BaseRepository<BookSuggestedQuestion, Long> {
}
