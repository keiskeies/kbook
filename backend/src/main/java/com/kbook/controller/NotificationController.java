package com.kbook.controller;

import com.kbook.common.api.PageResult;
import com.kbook.common.api.Result;
import com.kbook.entity.Notification;
import com.kbook.entity.User;
import com.kbook.repository.UserRepository;
import com.kbook.service.notification.NotificationService;
import com.kbook.dto.notification.NotificationVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 通知控制器
 */
@RestController
@RequestMapping("/api/user/notifications")
@RequiredArgsConstructor
@Tag(name = "通知")
public class NotificationController {

    private final NotificationService notificationService;
    private final UserRepository userRepository;

    /** 获取通知列表 */
    @Operation(summary = "获取通知列表")
    @GetMapping
    public Result<PageResult<NotificationVO>> getNotifications(
            Authentication auth,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        Long userId = (Long) auth.getPrincipal();
        Page<Notification> pageData = notificationService.getNotifications(userId, page, size);
        List<NotificationVO> vos = pageData.getContent().stream().map(this::toVO).toList();
        fillTriggerUser(vos);
        return Result.ok(PageResult.of(vos, pageData.getTotalElements(), page, size));
    }

    /** 未读数 */
    @Operation(summary = "获取未读通知数")
    @GetMapping("/unread-count")
    public Result<Long> getUnreadCount(Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        return Result.ok(notificationService.getUnreadCount(userId));
    }

    /** 标记已读 */
    @Operation(summary = "标记通知已读")
    @PutMapping("/{id}/read")
    public Result<Void> markAsRead(Authentication auth, @PathVariable Long id) {
        Long userId = (Long) auth.getPrincipal();
        notificationService.markAsRead(id, userId);
        return Result.ok();
    }

    /** 全部已读 */
    @Operation(summary = "全部标记已读")
    @PutMapping("/read-all")
    public Result<Void> markAllAsRead(Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        notificationService.markAllAsRead(userId);
        return Result.ok();
    }

    // ==================== VO ====================

    /**
     * 将通知实体转换为 VO
     * @param n 通知实体
     * @return 通知 VO
     */
    private NotificationVO toVO(Notification n) {
        NotificationVO vo = new NotificationVO();
        vo.setId(n.getId());
        vo.setTriggerUserId(n.getTriggerUserId());
        vo.setType(n.getType());
        vo.setCommentId(n.getCommentId());
        vo.setBookId(n.getBookId());
        vo.setIsRead(n.getIsRead());
        vo.setCreatedAt(n.getCreatedAt() != null ? n.getCreatedAt().toString() : null);
        return vo;
    }

    /**
     * 填充通知 VO 中的触发用户昵称和头像
     * @param vos 通知 VO 列表
     */
    private void fillTriggerUser(List<NotificationVO> vos) {
        if (vos == null || vos.isEmpty()) return;
        List<Long> userIds = vos.stream().map(NotificationVO::getTriggerUserId).distinct().toList();
        Map<Long, User> userMap = userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(User::getId, u -> u));
        for (NotificationVO vo : vos) {
            User user = userMap.get(vo.getTriggerUserId());
            if (user != null) {
                vo.setTriggerUserNickname(user.getNickname());
                vo.setTriggerUserAvatar(user.getAvatar());
            }
        }
    }
}
