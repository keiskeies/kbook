package com.kbook.repository;

import com.kbook.entity.AiConversation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * AI 对话记录 Repository
 */
public interface AiConversationRepository extends JpaRepository<AiConversation, Long> {

    /** 查询某个用户某次会话的所有消息（按时间正序） */
    List<AiConversation> findByUserIdAndSessionIdOrderByCreatedAtAsc(Long userId, String sessionId);

    /** 查询某个用户的会话 ID 列表（去重，按最近消息时间倒序，排除管理员会话） */
    @Query("SELECT c.sessionId FROM AiConversation c " +
            "WHERE c.userId = :userId AND c.sessionId NOT LIKE 'admin-%' " +
            "GROUP BY c.sessionId " +
            "ORDER BY MAX(c.createdAt) DESC")
    List<String> findSessionIdsByUserId(@Param("userId") Long userId);

    /** 删除某个用户某次会话的所有消息 */
    void deleteByUserIdAndSessionId(Long userId, String sessionId);

    /** 统计某个用户的会话数量 */
    @Query("SELECT COUNT(DISTINCT c.sessionId) FROM AiConversation c WHERE c.userId = :userId")
    long countDistinctSessionIdByUserId(@Param("userId") Long userId);

    /**
     * 统计所有用户的热门提问（role=user），按内容分组计数，取前 N 条
     * 排除过短的问题（<2字）和系统工具消息
     */
    @Query(value = "SELECT c.content FROM ai_conversations c " +
            "WHERE c.role = 'user' AND LENGTH(TRIM(c.content)) >= 2 " +
            "GROUP BY TRIM(c.content) " +
            "HAVING COUNT(*) >= 1 " +
            "ORDER BY COUNT(*) DESC " +
            "LIMIT :limit",
            nativeQuery = true)
    List<String> findHotPrompts(@Param("limit") int limit);
}
