package com.kbook.repository;

import com.kbook.entity.Conversation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 私信会话数据访问层
 */
@Repository
public interface ConversationRepository extends JpaRepository<Conversation, Long> {

    /**
     * 根据两个用户ID查询会话
     */
    Optional<Conversation> findByUser1IdAndUser2Id(Long user1Id, Long user2Id);

    /**
     * 查询用户参与的所有会话（排除已删除的），按更新时间降序排列
     */
    @Query("SELECT c FROM Conversation c WHERE (c.user1Id = :userId AND c.user1Deleted = false) OR (c.user2Id = :userId AND c.user2Deleted = false) ORDER BY c.updatedAt DESC")
    List<Conversation> findByUserIdOrderByUpdatedAtDesc(@Param("userId") Long userId);

    /**
     * 清零用户1的未读计数
     */
    @Modifying
    @Query("UPDATE Conversation c SET c.unreadCountUser1 = 0 WHERE c.id = :conversationId")
    void clearUnreadCountUser1(@Param("conversationId") Long conversationId);

    /**
     * 清零用户2的未读计数
     */
    @Modifying
    @Query("UPDATE Conversation c SET c.unreadCountUser2 = 0 WHERE c.id = :conversationId")
    void clearUnreadCountUser2(@Param("conversationId") Long conversationId);

    /**
     * 用户1软删除会话
     */
    @Modifying
    @Query("UPDATE Conversation c SET c.user1Deleted = true WHERE c.id = :conversationId")
    void deleteByUser1(@Param("conversationId") Long conversationId);

    /**
     * 用户2软删除会话
     */
    @Modifying
    @Query("UPDATE Conversation c SET c.user2Deleted = true WHERE c.id = :conversationId")
    void deleteByUser2(@Param("conversationId") Long conversationId);

    /**
     * 统计用户的总未读消息数
     */
    @Query("SELECT COALESCE(SUM(CASE WHEN c.user1Id = :userId THEN c.unreadCountUser1 ELSE c.unreadCountUser2 END), 0) FROM Conversation c WHERE (c.user1Id = :userId OR c.user2Id = :userId)")
    Long sumUnreadCount(@Param("userId") Long userId);
}