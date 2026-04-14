package com.kbook.repository;

import com.kbook.entity.Notification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 系统通知数据访问层
 */
public interface NotificationRepository extends JpaRepository<Notification, Long> {

    /** 查询用户的通知列表（最新优先） */
    Page<Notification> findByReceiverIdOrderByCreatedAtDesc(Long receiverId, Pageable pageable);

    /** 统计未读通知数 */
    long countByReceiverIdAndIsReadFalse(Long receiverId);

    /** 全部标记为已读 */
    @Modifying
    @Query("UPDATE Notification n SET n.isRead = true WHERE n.receiverId = :receiverId AND n.isRead = false")
    void markAllAsRead(@Param("receiverId") Long receiverId);
}
