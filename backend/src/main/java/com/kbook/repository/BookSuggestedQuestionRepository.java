package com.kbook.repository;

import com.kbook.entity.BookSuggestedQuestion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 图书预设问题数据访问层
 */
@Repository
public interface BookSuggestedQuestionRepository extends JpaRepository<BookSuggestedQuestion, Long> {

    /**
     * 根据图书ID查询所有预设问题
     */
    List<BookSuggestedQuestion> findByBookId(Long bookId);

    Long countByBookId(Long bookId);

    /**
     * 删除指定图书的所有预设问题
     */
    void deleteByBookId(Long bookId);
}
