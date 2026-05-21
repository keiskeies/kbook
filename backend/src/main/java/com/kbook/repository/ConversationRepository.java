package com.kbook.repository;

import com.kbook.entity.Conversation;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ConversationRepository extends JpaRepository<Conversation, Long> {

    Optional<Conversation> findByUser1IdAndUser2Id(Long user1Id, Long user2Id);

    @Query("SELECT c FROM Conversation c WHERE (c.user1Id = :userId AND c.user1Deleted = false) OR (c.user2Id = :userId AND c.user2Deleted = false) ORDER BY c.updatedAt DESC")
    List<Conversation> findByUserIdOrderByUpdatedAtDesc(@Param("userId") Long userId);

    @Query("SELECT c FROM Conversation c WHERE ((c.user1Id = :userId AND c.user1Deleted = false) OR (c.user2Id = :userId AND c.user2Deleted = false)) AND c.lastMessage LIKE %:keyword% ORDER BY c.updatedAt DESC")
    List<Conversation> findByUserIdAndKeyword(@Param("userId") Long userId, @Param("keyword") String keyword);

    @Modifying
    @Query("UPDATE Conversation c SET c.unreadCountUser1 = 0 WHERE c.id = :conversationId")
    void clearUnreadCountUser1(@Param("conversationId") Long conversationId);

    @Modifying
    @Query("UPDATE Conversation c SET c.unreadCountUser2 = 0 WHERE c.id = :conversationId")
    void clearUnreadCountUser2(@Param("conversationId") Long conversationId);

    @Modifying
    @Query("UPDATE Conversation c SET c.user1Deleted = true WHERE c.id = :conversationId")
    void deleteByUser1(@Param("conversationId") Long conversationId);

    @Modifying
    @Query("UPDATE Conversation c SET c.user2Deleted = true WHERE c.id = :conversationId")
    void deleteByUser2(@Param("conversationId") Long conversationId);

    @Query("SELECT COALESCE(SUM(CASE WHEN c.user1Id = :userId THEN c.unreadCountUser1 ELSE c.unreadCountUser2 END), 0) FROM Conversation c WHERE (c.user1Id = :userId OR c.user2Id = :userId)")
    Long sumUnreadCount(@Param("userId") Long userId);
}