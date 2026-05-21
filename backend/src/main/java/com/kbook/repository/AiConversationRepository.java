package com.kbook.repository;

import com.kbook.entity.AiConversation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AiConversationRepository extends JpaRepository<AiConversation, Long> {

    List<AiConversation> findByUserIdAndSessionIdOrderByCreatedAtAsc(Long userId, String sessionId);

    @Query("SELECT c.sessionId FROM AiConversation c " +
            "WHERE c.userId = :userId AND c.type = :type " +
            "GROUP BY c.sessionId " +
            "ORDER BY MAX(c.createdAt) DESC")
    List<String> findSessionIdsByUserIdAndType(@Param("userId") Long userId, @Param("type") String type);

    @Query("SELECT c.sessionId FROM AiConversation c " +
            "WHERE c.userId = :userId AND c.type = :type AND c.bookId = :bookId " +
            "GROUP BY c.sessionId " +
            "ORDER BY MAX(c.createdAt) DESC")
    List<String> findSessionIdsByUserIdAndTypeAndBookId(@Param("userId") Long userId, @Param("type") String type, @Param("bookId") Long bookId);

    void deleteByUserIdAndSessionId(Long userId, String sessionId);

    @Query(value = "SELECT a.* FROM ai_conversations a " +
            "WHERE a.book_id = :bookId AND a.role = 'assistant' " +
            "AND a.session_id IN (SELECT u.session_id FROM ai_conversations u " +
            "WHERE u.book_id = :bookId AND u.role = 'user' AND u.content = :question) " +
            "ORDER BY a.created_at DESC LIMIT 1",
            nativeQuery = true)
    Optional<AiConversation> findCachedAnswer(@Param("bookId") Long bookId, @Param("question") String question);

    @Query("SELECT COUNT(DISTINCT c.sessionId) FROM AiConversation c WHERE c.userId = :userId")
    long countDistinctSessionIdByUserId(@Param("userId") Long userId);

    @Query(value = "SELECT trimmed_content FROM (" +
            "SELECT TRIM(c.content) AS trimmed_content, COUNT(*) AS cnt " +
            "FROM ai_conversations c " +
            "WHERE c.role = 'user' AND LENGTH(TRIM(c.content)) >= 2 " +
            "GROUP BY TRIM(c.content) " +
            "HAVING cnt >= 1 " +
            "ORDER BY cnt DESC " +
            "LIMIT :limit" +
            ") t",
            nativeQuery = true)
    List<String> findHotPrompts(@Param("limit") int limit);
}
