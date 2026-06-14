package com.kbook.controller;

import com.kbook.common.api.Result;
import com.kbook.dto.admin.AdminSendCodeRequest;
import com.kbook.dto.auth.BindEmailRequest;
import com.kbook.dto.user.UserInfo;
import com.kbook.entity.User;
import com.kbook.service.auth.AuthService;
import com.kbook.service.user.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/**
 * 管理员账户控制器 — 邮箱绑定等账户操作
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/account")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "账户管理")
public class AdminAccountController {

    private final UserService userService;
    private final AuthService authService;

    @Operation(summary = "发送绑定邮箱验证码")
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

    @Operation(summary = "绑定邮箱")
    @PostMapping("/bind-email")
    public Result<UserInfo> bindEmail(Authentication authentication,
                                      @RequestBody @jakarta.validation.Valid BindEmailRequest req) {
        Long userId = (Long) authentication.getPrincipal();

        authService.validateBindCode(req.getEmail(), req.getCode());

        User user = userService.bindEmail(userId, req.getEmail());
        return Result.ok(UserInfo.from(user));
    }
}
