package com.kbook.repository;

import com.kbook.entity.ChatMessage;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;

@Repository
public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    Page<ChatMessage> findByConversationIdOrderByCreatedAtDesc(Long conversationId, Pageable pageable);

    @Modifying
    @Query("UPDATE ChatMessage m SET m.read = true WHERE m.conversationId = :conversationId AND m.recipientId = :recipientId AND m.read = false")
    void markAllAsRead(@Param("conversationId") Long conversationId, @Param("recipientId") Long recipientId);

    @Query("SELECT COUNT(m) FROM ChatMessage m WHERE m.conversationId = :conversationId AND m.senderId = :senderId")
    Long countByConversationIdAndSenderId(@Param("conversationId") Long conversationId, @Param("senderId") Long senderId);

    @Query("SELECT COUNT(m) FROM ChatMessage m WHERE m.senderId = :senderId AND m.recipientId = :recipientId AND m.createdAt > :since")
    Long countRecentMessages(@Param("senderId") Long senderId, @Param("recipientId") Long recipientId, @Param("since") LocalDateTime since);

    @Query("SELECT COUNT(m) > 0 FROM ChatMessage m JOIN Conversation c ON m.conversationId = c.id " +
           "WHERE m.fileUrl = :fileUrl AND (c.user1Id = :userId OR c.user2Id = :userId)")
    boolean existsByFileUrlAndParticipants(@Param("fileUrl") String fileUrl, @Param("userId") Long userId);
}