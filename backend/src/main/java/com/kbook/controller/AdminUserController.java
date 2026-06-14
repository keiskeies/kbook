package com.kbook.controller;

import com.kbook.common.api.PageResult;
import com.kbook.common.api.Result;
import com.kbook.dto.admin.AdminBatchRequest;
import com.kbook.dto.admin.AdminBatchResult;
import com.kbook.dto.admin.InviteRequest;
import com.kbook.dto.admin.InviteResult;
import com.kbook.dto.book.BookProjection;
import com.kbook.entity.User;
import com.kbook.service.book.BookService;
import com.kbook.service.notification.EmailNotificationService;
import com.kbook.service.user.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 管理员用户管理控制器 — 用户审核、列表、封禁、邀请注册
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "用户管理")
public class AdminUserController {

    private final UserService userService;
    private final BookService bookService;
    private final EmailNotificationService emailNotificationService;

    // ==================== 审核统计 ====================

    @Operation(summary = "审核统计")
    @GetMapping("/stats")
    public Result<Map<String, Long>> getStats() {
        return Result.ok(userService.getReviewStats());
    }

    // ==================== 用户列表 ====================

    @Operation(summary = "获取待审核用户")
    @GetMapping("/pending")
    public Result<PageResult<User>> getPendingUsers(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        return Result.ok(userService.getPendingUsers(page, size));
    }

    @Operation(summary = "按状态筛选用户")
    @GetMapping
    public Result<PageResult<User>> getUsersByStatus(
            @RequestParam(required = false) List<String> statuses,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        return Result.ok(userService.getUsersByStatus(statuses, page, size));
    }

    @Operation(summary = "搜索用户")
    @GetMapping("/search")
    public Result<PageResult<User>> searchUsers(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        return Result.ok(userService.searchUsers(keyword, status, page, size));
    }

    // ==================== 审核操作 ====================

    @Operation(summary = "审核通过")
    @PostMapping("/{userId}/approve")
    public Result<Void> approveUser(@PathVariable Long userId) {
        userService.approveUser(userId);
        return Result.ok();
    }

    @Operation(summary = "批量审核通过")
    @PostMapping("/batch-approve")
    public Result<AdminBatchResult> batchApprove(@RequestBody AdminBatchRequest req) {
        int count = userService.batchApprove(req.getUserIds());
        return Result.ok(new AdminBatchResult(count));
    }

    @Operation(summary = "审核拒绝")
    @PostMapping("/{userId}/reject")
    public Result<Void> rejectUser(@PathVariable Long userId) {
        userService.rejectUser(userId);
        return Result.ok();
    }

    @Operation(summary = "批量拒绝")
    @PostMapping("/batch-reject")
    public Result<AdminBatchResult> batchReject(@RequestBody AdminBatchRequest req) {
        int count = userService.batchReject(req.getUserIds());
        return Result.ok(new AdminBatchResult(count));
    }

    @Operation(summary = "解封用户")
    @PostMapping("/{userId}/unban")
    public Result<Void> unbanUser(@PathVariable Long userId) {
        userService.unbanUser(userId);
        return Result.ok();
    }

    @Operation(summary = "封禁用户")
    @PostMapping("/{userId}/ban")
    public Result<Void> banUser(@PathVariable Long userId) {
        userService.banUser(userId);
        return Result.ok();
    }

    // ==================== 邀请注册 ====================

    @Operation(summary = "发送邀请邮件")
    @PostMapping("/invite")
    public Result<InviteResult> sendInvitation(Authentication authentication,
                                               @RequestBody @jakarta.validation.Valid InviteRequest req) {
        Long adminId = (Long) authentication.getPrincipal();
        User admin = userService.getUserById(adminId);

        String bookTitle = "KBook";
        if (req.getBookId() != null) {
            try {
                BookProjection book = bookService.getBookProjectionById(req.getBookId());
                bookTitle = book.getTitle();
            } catch (Exception e) {
                // 图书不存在时使用默认标题
            }
        }

        String inviteCode = emailNotificationService.sendInvitation(
                req.getEmail(),
                admin.getNickname(),
                bookTitle
        );

        return Result.ok(new InviteResult(req.getEmail(), inviteCode));
    }
}
