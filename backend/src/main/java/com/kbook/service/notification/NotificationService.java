package com.kbook.service.notification;

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
public class NotificationService {

    /** 通知数据仓库 */
    @Autowired
    private NotificationRepository notificationRepository;
    /** 用户关注数据仓库（用于查询粉丝列表） */
    @Autowired
    private UserFollowRepository userFollowRepository;
    /** 用户数据仓库 */
    @Autowired
    private UserRepository userRepository;

    /**
     * 评论被回复时发送通知给被回复者
     * 当用户的书评收到新回复时触发，提醒用户查看回复内容
     *
     * @param triggerUserId 触发操作的用户ID（回复者）
     * @param receiverId    接收通知的用户ID（被回复者）
     * @param commentId     被回复的评论ID
     * @param bookId        评论所在的书籍ID
     */
    @Transactional
    public void notifyCommentReply(Long triggerUserId, Long receiverId, Long commentId, Long bookId) {
        createNotification(triggerUserId, receiverId, "COMMENT_REPLY", commentId, bookId);
    }

    /**
     * 评论被点赞时发送通知给评论作者
     * 当用户的书评获得点赞时触发，提升用户参与感
     *
     * @param triggerUserId 触发操作的用户ID（点赞者）
     * @param receiverId    接收通知的用户ID（评论作者）
     * @param commentId     被点赞的评论ID
     * @param bookId        评论所在的书籍ID
     */
    @Transactional
    public void notifyCommentLiked(Long triggerUserId, Long receiverId, Long commentId, Long bookId) {
        createNotification(triggerUserId, receiverId, "COMMENT_LIKED", commentId, bookId);
    }

    /**
     * 评论被收藏时发送通知给评论作者
     * 当用户的书评被其他用户收藏时触发
     *
     * @param triggerUserId 触发操作的用户ID（收藏者）
     * @param receiverId    接收通知的用户ID（评论作者）
     * @param commentId     被收藏的评论ID
     * @param bookId        评论所在的书籍ID
     */
    @Transactional
    public void notifyCommentFavorited(Long triggerUserId, Long receiverId, Long commentId, Long bookId) {
        createNotification(triggerUserId, receiverId, "COMMENT_FAVORITED", commentId, bookId);
    }

    /**
     * 关注的人发表新书评时，通知所有粉丝
     * 采用一对多通知模式：遍历粉丝列表逐一发送，用于提升社区活跃度
     *
     * @param authorId  发表书评的作者ID
     * @param commentId 新书评ID
     * @param bookId    书评所在的书籍ID
     */
    @Transactional
    public void notifyFollowersNewReview(Long authorId, Long commentId, Long bookId) {
        // 查询该用户的所有粉丝
        List<UserFollow> followers = userFollowRepository.findFollowers(authorId);
        // 逐一为每个粉丝创建通知
        for (UserFollow follow : followers) {
            createNotification(authorId, follow.getFollowerId(), "NEW_REVIEW", commentId, bookId);
        }
    }

    /**
     * 创建站内通知（自动过滤自身通知）
     * 防止用户触发操作后收到自己的通知，减少无效打扰
     *
     * @param triggerUserId 触发操作的用户ID
     * @param receiverId    接收通知的用户ID
     * @param type          通知类型（COMMENT_REPLY / COMMENT_LIKED / COMMENT_FAVORITED / NEW_REVIEW）
     * @param commentId     关联的评论ID
     * @param bookId        关联的书籍ID
     */
    private void createNotification(Long triggerUserId, Long receiverId, String type, Long commentId, Long bookId) {
        // 不通知自己：触发者和接收者相同时跳过
        if (triggerUserId.equals(receiverId)) return;
        Notification notification = Notification.builder()
                .receiverId(receiverId)
                .triggerUserId(triggerUserId)
                .type(type)
                .commentId(commentId)
                .bookId(bookId)
                .build();
        notificationRepository.save(notification);
    }

    /**
     * 分页获取用户通知列表
     * 按创建时间降序排列，最新的通知排在最前面
     *
     * @param userId 用户ID
     * @param page   页码（从1开始，内部自动转换为0-based）
     * @param size   每页大小
     * @return 分页的通知列表
     */
    public Page<Notification> getNotifications(Long userId, int page, int size) {
        // Spring Data 分页从0开始，前端传入的page从1开始，需减1转换
        Pageable pageable = PageRequest.of(page - 1, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        return notificationRepository.findByReceiverIdOrderByCreatedAtDesc(userId, pageable);
    }

    /**
     * 获取用户的未读通知数量
     * 用于前端展示未读通知角标（红点/数字）
     *
     * @param userId 用户ID
     * @return 未读通知数量
     */
    public long getUnreadCount(Long userId) {
        return notificationRepository.countByReceiverIdAndIsReadFalse(userId);
    }

    /**
     * 标记单条通知为已读
     * 需验证操作权限：只有通知接收者才能标记自己的通知
     *
     * @param notificationId 通知ID
     * @param userId         操作用户ID（必须是通知接收者）
     * @throws RuntimeException 通知不存在或无权操作时抛出
     */
    @Transactional
    public void markAsRead(Long notificationId, Long userId) {
        Notification notification = notificationRepository.findOneById(notificationId);
        if (notification == null) {
            throw new RuntimeException("通知不存在");
        }
        // 权限校验：只能标记自己收到的通知
        if (!notification.getReceiverId().equals(userId)) {
            throw new RuntimeException("无权操作");
        }
        notification.setIsRead(true);
        notificationRepository.save(notification);
    }

    /**
     * 将用户所有未读通知批量标记为已读
     * 一键已读功能，提升用户体验
     *
     * @param userId 用户ID
     */
    @Transactional
    public void markAllAsRead(Long userId) {
        notificationRepository.markAllAsRead(userId);
    }
}
