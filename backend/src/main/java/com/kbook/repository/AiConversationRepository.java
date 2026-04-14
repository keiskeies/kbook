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

    /** 查询某个用户的会话 ID 列表（去重，按最近消息时间倒序） */
    @Query("SELECT c.sessionId FROM AiConversation c " +
            "WHERE c.userId = :userId " +
            "GROUP BY c.sessionId " +
            "ORDER BY MAX(c.createdAt) DESC")
    List<String> findSessionIdsByUserId(@Param("userId") Long userId);

    /** 删除某个用户某次会话的所有消息 */
    void deleteByUserIdAndSessionId(Long userId, String sessionId);

    /** 统计某个用户的会话数量 */
    @Query("SELECT COUNT(DISTINCT c.sessionId) FROM AiConversation c WHERE c.userId = :userId")
    long countDistinctSessionIdByUserId(@Param("userId") Long userId);
}
