package com.kbook.service;

import com.kbook.common.service.AbstractServiceImpl;
import com.kbook.entity.Notification;
import com.kbook.entity.UserFollow;
import com.kbook.repository.NotificationRepository;
import com.kbook.repository.UserFollowRepository;
import com.kbook.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 通知服务
 * <p>
 * 管理用户通知的创建、查询和已读标记。
 * 支持评论回复、评论点赞、评论收藏、关注用户新书评等通知类型。
 */
@Slf4j
@Service
public class NotificationService extends AbstractServiceImpl<Notification, Long> {

    /** 通知数据仓库 */
    @Autowired
    private NotificationRepository notificationRepository;
    /** 用户关注数据仓库（用于查询粉丝列表） */
    @Autowired
    private UserFollowRepository userFollowRepository;
    /** 用户数据仓库 */
    @Autowired
    private UserRepository userRepository;

    /** 评论被回复 */
    @Transactional
    public void notifyCommentReply(Long triggerUserId, Long receiverId, Long commentId, Long bookId) {
        createNotification(triggerUserId, receiverId, "COMMENT_REPLY", commentId, bookId);
    }

    /** 评论被点赞 */
    @Transactional
    public void notifyCommentLiked(Long triggerUserId, Long receiverId, Long commentId, Long bookId) {
        createNotification(triggerUserId, receiverId, "COMMENT_LIKED", commentId, bookId);
    }

    /** 评论被收藏 */
    @Transactional
    public void notifyCommentFavorited(Long triggerUserId, Long receiverId, Long commentId, Long bookId) {
        createNotification(triggerUserId, receiverId, "COMMENT_FAVORITED", commentId, bookId);
    }

    /** 关注的人发表书评 — 通知所有粉丝 */
    @Transactional
    public void notifyFollowersNewReview(Long authorId, Long commentId, Long bookId) {
        List<UserFollow> followers = userFollowRepository.findFollowers(authorId);
        for (UserFollow follow : followers) {
            createNotification(authorId, follow.getFollowerId(), "NEW_REVIEW", commentId, bookId);
        }
    }

    /** 创建通知（不通知自己） */
    private void createNotification(Long triggerUserId, Long receiverId, String type, Long commentId, Long bookId) {
        if (triggerUserId.equals(receiverId)) return; // 不通知自己
        Notification notification = Notification.builder()
                .receiverId(receiverId)
                .triggerUserId(triggerUserId)
                .type(type)
                .commentId(commentId)
                .bookId(bookId)
                .build();
        saveOne(notification);
    }

    /** 获取用户通知列表 */
    public Page<Notification> getNotifications(Long userId, int page, int size) {
        Pageable pageable = PageRequest.of(page - 1, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        return notificationRepository.findByReceiverIdOrderByCreatedAtDesc(userId, pageable);
    }

    /** 获取未读通知数 */
    public long getUnreadCount(Long userId) {
        return notificationRepository.countByReceiverIdAndIsReadFalse(userId);
    }

    /** 标记单条通知已读 */
    @Transactional
    public void markAsRead(Long notificationId, Long userId) {
        Notification notification = findOneById(notificationId);
        if (notification == null) {
            throw new RuntimeException("通知不存在");
        }
        if (!notification.getReceiverId().equals(userId)) {
            throw new RuntimeException("无权操作");
        }
        notification.setIsRead(true);
        updateOne(notification);
    }

    /** 全部标记已读 */
    @Transactional
    public void markAllAsRead(Long userId) {
        notificationRepository.markAllAsRead(userId);
    }
}
