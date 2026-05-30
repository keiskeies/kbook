package com.kbook.controller;

import com.kbook.common.api.PageResult;
import com.kbook.common.api.Result;
import com.kbook.dto.UserInfo;
import com.kbook.entity.Book;
import com.kbook.entity.User;
import com.kbook.service.AuthService;
import com.kbook.service.BookService;
import com.kbook.service.EmailNotificationService;
import com.kbook.service.UserService;
import com.kbook.dto.AdminBatchRequest;
import com.kbook.dto.AdminBatchResult;
import com.kbook.dto.AdminSendCodeRequest;
import com.kbook.dto.BindEmailRequest;
import com.kbook.dto.InviteRequest;
import com.kbook.dto.InviteResult;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 管理员控制器 - 用户审核 + 管理员绑定邮箱
 * <p>
 * 权限隔离逻辑：
 * - 类级别 @PreAuthorize("hasRole('ADMIN')") 确保所有接口仅管理员可访问
 * - SecurityConfig 中 /api/admin/** 路径要求 ADMIN 角色
 * - JWT 过滤器从 Token 解析 role 并注入 ROLE_ADMIN 权限
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminController {

    private final UserService userService;
    private final AuthService authService;
    private final EmailNotificationService emailNotificationService;
    private final BookService bookService;

    // ==================== 审核统计 ====================

    /**
     * 审核统计
     */
    @GetMapping("/stats")
    public Result<Map<String, Long>> getStats() {
        return Result.ok(userService.getReviewStats());
    }

    // ==================== 用户列表 ====================

    /**
     * 分页查询待审核用户
     */
    @GetMapping("/users/pending")
    public Result<PageResult<User>> getPendingUsers(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        return Result.ok(userService.getPendingUsers(page, size));
    }

    /**
     * 按状态筛选用户
     *
     * @param statuses 状态列表，如 ?statuses=PENDING&statuses=BANNED
     */
    @GetMapping("/users")
    public Result<PageResult<User>> getUsersByStatus(
            @RequestParam(required = false) List<String> statuses,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        return Result.ok(userService.getUsersByStatus(statuses, page, size));
    }

    /**
     * 搜索用户（关键词 + 状态）
     */
    @GetMapping("/users/search")
    public Result<PageResult<User>> searchUsers(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        return Result.ok(userService.searchUsers(keyword, status, page, size));
    }

    // ==================== 审核操作 ====================

    /**
     * 审核通过
     */
    @PostMapping("/users/{userId}/approve")
    public Result<Void> approveUser(@PathVariable Long userId) {
        userService.approveUser(userId);
        return Result.ok();
    }

    /**
     * 批量审核通过
     */
    @PostMapping("/users/batch-approve")
    public Result<AdminBatchResult> batchApprove(@RequestBody AdminBatchRequest req) {
        int count = userService.batchApprove(req.getUserIds());
        return Result.ok(new AdminBatchResult(count));
    }

    /**
     * 审核拒绝（封禁）
     */
    @PostMapping("/users/{userId}/reject")
    public Result<Void> rejectUser(@PathVariable Long userId) {
        userService.rejectUser(userId);
        return Result.ok();
    }

    /**
     * 批量拒绝
     */
    @PostMapping("/users/batch-reject")
    public Result<AdminBatchResult> batchReject(@RequestBody AdminBatchRequest req) {
        int count = userService.batchReject(req.getUserIds());
        return Result.ok(new AdminBatchResult(count));
    }

    /**
     * 解封用户
     */
    @PostMapping("/users/{userId}/unban")
    public Result<Void> unbanUser(@PathVariable Long userId) {
        userService.unbanUser(userId);
        return Result.ok();
    }

    /**
     * 封禁用户（封禁已通过的用户）
     */
    @PostMapping("/users/{userId}/ban")
    public Result<Void> banUser(@PathVariable Long userId) {
        userService.banUser(userId);
        return Result.ok();
    }

    // ==================== 管理员绑定邮箱 ====================

    /**
     * 管理员发送绑定邮箱验证码
     */
    @PostMapping("/bind-email/send-code")
    public Result<Void> sendBindEmailCode(Authentication authentication,
                                          @RequestBody @jakarta.validation.Valid AdminSendCodeRequest req) {
        Long userId = (Long) authentication.getPrincipal();
        User user = userService.getUserById(userId);
        if (Boolean.TRUE.equals(user.getEmailBound())) {
            return Result.fail("邮箱已绑定，无需重复绑定");
        }
        authService.sendVerificationCode(req.getEmail(), "bind", null);
        return Result.ok();
    }

    /**
     * 管理员绑定邮箱（需验证码）
     * 绑定后开启密码重置功能
     */
    @PostMapping("/bind-email")
    public Result<UserInfo> bindEmail(Authentication authentication,
                                      @RequestBody @jakarta.validation.Valid BindEmailRequest req) {
        Long userId = (Long) authentication.getPrincipal();

        // 先校验验证码
        authService.validateBindCode(req.getEmail(), req.getCode());

        User user = userService.bindEmail(userId, req.getEmail());
        return Result.ok(UserInfo.from(user));
    }

    // ==================== 邀请注册 ====================

    /**
     * 管理员发送邀请邮件
     */
    @PostMapping("/invite")
    public Result<InviteResult> sendInvitation(Authentication authentication,
                                               @RequestBody @jakarta.validation.Valid InviteRequest req) {
        Long adminId = (Long) authentication.getPrincipal();
        User admin = userService.getUserById(adminId);

        // 获取图书信息
        String bookTitle = "KBook";
        if (req.getBookId() != null) {
            Book book = bookService.getBookById(req.getBookId());
            if (book != null) {
                bookTitle = book.getTitle();
            }
        }

        // 发送邀请邮件
        String inviteCode = emailNotificationService.sendInvitation(
                req.getEmail(),
                admin.getNickname(),
                bookTitle
        );

        return Result.ok(new InviteResult(req.getEmail(), inviteCode));
    }
}
