package com.kbook.repository;

import com.kbook.common.repository.BaseRepository;
import com.kbook.entity.AnswerFeedback;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** 回答反馈仓储（方案 P2-5）。 */
public interface AnswerFeedbackRepository extends BaseRepository<AnswerFeedback, Long> {

    Optional<AnswerFeedback> findByConversationIdAndUserId(Long conversationId, Long userId);

    void deleteByConversationId(Long conversationId);

    Page<AnswerFeedback> findByReviewStatusOrderByCreatedAtDesc(String reviewStatus, Pageable pageable);

    /** 打开一段历史会话时一次性取回该用户对这些回答的反馈，避免 N 次请求 */
    List<AnswerFeedback> findByUserIdAndConversationIdIn(Long userId, Collection<Long> conversationIds);

    long countByReviewStatus(String reviewStatus);

    long countByRating(String rating);

    @Query("SELECT f.rating, COUNT(f) FROM AnswerFeedback f WHERE f.bookId = :bookId GROUP BY f.rating")
    List<Object[]> countByRatingForBook(@Param("bookId") Long bookId);
}
