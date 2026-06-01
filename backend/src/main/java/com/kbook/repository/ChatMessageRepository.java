package com.kbook.repository;

import com.kbook.entity.ChatMessage;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 聊天消息数据访问层
 */
@Repository
public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    /**
     * 按会话ID分页查询消息，按创建时间降序排列
     */
    Page<ChatMessage> findByConversationIdOrderByCreatedAtDesc(Long conversationId, Pageable pageable);

    /**
     * 查询会话中指定ID之前的消息（用于加载历史消息），按创建时间降序排列
     */
    List<ChatMessage> findByConversationIdAndIdLessThanOrderByCreatedAtDesc(Long conversationId, Long id, Pageable pageable);

    /**
     * 查询会话中最近20条消息，按创建时间降序排列
     */
    List<ChatMessage> findTop20ByConversationIdOrderByCreatedAtDesc(Long conversationId);

    /**
     * 将会话中指定接收者的所有未读消息标记为已读
     */
    @Modifying
    @Transactional
    @Query("UPDATE ChatMessage m SET m.read = true WHERE m.conversationId = :conversationId AND m.recipientId = :recipientId AND m.read = false")
    void markAllAsRead(@Param("conversationId") Long conversationId, @Param("recipientId") Long recipientId);

    /**
     * 统计会话中指定发送者的消息数量
     */
    @Query("SELECT COUNT(m) FROM ChatMessage m WHERE m.conversationId = :conversationId AND m.senderId = :senderId")
    Long countByConversationIdAndSenderId(@Param("conversationId") Long conversationId, @Param("senderId") Long senderId);

    /**
     * 统计指定时间之后发送者发给接收者的消息数量（用于频率限制）
     */
    @Query("SELECT COUNT(m) FROM ChatMessage m WHERE m.senderId = :senderId AND m.recipientId = :recipientId AND m.createdAt > :since")
    Long countRecentMessages(@Param("senderId") Long senderId, @Param("recipientId") Long recipientId, @Param("since") LocalDateTime since);

    /**
     * 判断文件是否在用户参与的会话中发送过（用于文件访问权限校验）
     */
    @Query("SELECT COUNT(m) > 0 FROM ChatMessage m JOIN Conversation c ON m.conversationId = c.id " +
           "WHERE m.fileUrl = :fileUrl AND (c.user1Id = :userId OR c.user2Id = :userId)")
    boolean existsByFileUrlAndParticipants(@Param("fileUrl") String fileUrl, @Param("userId") Long userId);
}