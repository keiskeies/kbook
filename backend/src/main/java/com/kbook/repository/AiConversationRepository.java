package com.kbook.repository;

import com.kbook.entity.AiConversation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * AI 对话记录数据访问层
 */
public interface AiConversationRepository extends JpaRepository<AiConversation, Long> {

    /**
     * 按会话ID查询对话记录，按创建时间升序排列
     */
    List<AiConversation> findBySessionIdOrderByCreatedAtAsc(String sessionId);

    /**
     * 按用户和会话ID查询对话记录，按创建时间升序排列
     */
    List<AiConversation> findByUserIdAndSessionIdOrderByCreatedAtAsc(Long userId, String sessionId);

    /**
     * 按用户和类型查询所有会话ID，按最近活跃时间降序排列
     */
    @Query("SELECT c.sessionId FROM AiConversation c " +
            "WHERE c.userId = :userId AND c.type = :type " +
            "GROUP BY c.sessionId " +
            "ORDER BY MAX(c.createdAt) DESC")
    List<String> findSessionIdsByUserIdAndType(@Param("userId") Long userId, @Param("type") String type);

    /**
     * 按用户、类型和图书ID查询所有会话ID，按最近活跃时间降序排列
     */
    @Query("SELECT c.sessionId FROM AiConversation c " +
            "WHERE c.userId = :userId AND c.type = :type AND c.bookId = :bookId " +
            "GROUP BY c.sessionId " +
            "ORDER BY MAX(c.createdAt) DESC")
    List<String> findSessionIdsByUserIdAndTypeAndBookId(@Param("userId") Long userId, @Param("type") String type, @Param("bookId") Long bookId);

    /**
     * 删除指定用户和会话ID的所有对话记录
     */
    void deleteByUserIdAndSessionId(Long userId, String sessionId);

    /**
     * 查找缓存回答：根据图书ID和用户问题，查找最近一条助手回复
     */
    @Query(value = "SELECT a.* FROM ai_conversations a " +
            "WHERE a.book_id = :bookId AND a.role = 'assistant' " +
            "AND a.session_id IN (SELECT u.session_id FROM ai_conversations u " +
            "WHERE u.book_id = :bookId AND u.role = 'user' AND u.content = :question) " +
            "ORDER BY a.created_at DESC LIMIT 1",
            nativeQuery = true)
    Optional<AiConversation> findCachedAnswer(@Param("bookId") Long bookId, @Param("question") String question);

    /**
     * 统计用户的会话数量（按会话ID去重）
     */
    @Query("SELECT COUNT(DISTINCT c.sessionId) FROM AiConversation c WHERE c.userId = :userId")
    long countDistinctSessionIdByUserId(@Param("userId") Long userId);

    /**
     * 统计会话 compressed_content 总长度（字符数）
     */
    @Query(value = "SELECT COALESCE(SUM(CHAR_LENGTH(compressed_content)), 0) " +
            "FROM ai_conversations WHERE user_id = :userId AND session_id = :sessionId",
            nativeQuery = true)
    long sumCompressedContentLength(@Param("userId") Long userId, @Param("sessionId") String sessionId);

    /**
     * 查找会话中最老的一条未压缩 assistant 消息
     * 未压缩 = compressed_content 与 content 完全相同（创建时初始化为相等）
     */
    @Query(value = "SELECT * FROM ai_conversations " +
            "WHERE user_id = :userId AND session_id = :sessionId AND role = 'assistant' " +
            "AND COALESCE(compressed_content, content) = content " +
            "ORDER BY created_at ASC LIMIT 1",
            nativeQuery = true)
    Optional<AiConversation> findFirstUncompressedAssistant(@Param("userId") Long userId, @Param("sessionId") String sessionId);

    /**
     * 查询热门提示词：统计用户消息中出现频率最高的内容
     */
    @Query(value = "SELECT trimmed_content FROM (" +
            "SELECT TRIM(c.content) AS trimmed_content, COUNT(*) AS cnt " +
            "FROM ai_conversations c " +
            "WHERE c.role = 'user' AND c.type = 'assistant' AND LENGTH(TRIM(c.content)) >= 2 " +
            "GROUP BY TRIM(c.content) " +
            "HAVING cnt >= 1 " +
            "ORDER BY cnt DESC " +
            "LIMIT :limit" +
            ") t",
            nativeQuery = true)
    List<String> findHotPrompts(@Param("limit") int limit);
}
