package com.kbook.controller;

import com.kbook.common.api.Result;
import com.kbook.dto.*;
import com.kbook.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证控制器
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Validated
@Tag(name = "认证")
public class AuthController {

    private final AuthService authService;

    /**
     * 发送验证码
     * @param req 场景：register | login | reset
     */
    @Operation(summary = "发送验证码")
    @PostMapping("/send-code")
    public Result<Void> sendCode(@RequestBody @Validated AuthSendCodeRequest req) {
        authService.sendVerificationCode(req.getEmail(), req.getScene(), req.getCaptchaId());
        return Result.ok();
    }

    /**
     * 验证码登录
     */
    @Operation(summary = "验证码登录")
    @PostMapping("/login/code")
    public Result<LoginResult> loginByCode(@RequestBody @Validated CodeLoginRequest req) {
        return Result.ok(authService.loginByCode(req.getEmail(), req.getCode()));
    }

    /**
     * 密码登录
     */
    @Operation(summary = "密码登录")
    @PostMapping("/login/password")
    public Result<LoginResult> loginByPassword(@RequestBody @Validated PasswordLoginRequest req) {
        return Result.ok(authService.loginByPassword(req.getEmail(), req.getPassword(), req.getCaptchaId()));
    }

    /**
     * 注册
     */
    @Operation(summary = "注册")
    @PostMapping("/register")
    public Result<LoginResult> register(@RequestBody @Validated RegisterRequest req) {
        return Result.ok(authService.register(
                req.getEmail(), req.getCode(), req.getPassword(),
                req.getBirthday(), req.getGender(), req.getMarried(), req.getHasChildren(), req.getMbti()));
    }

    /**
     * 刷新 Token
     */
    @Operation(summary = "刷新Token")
    @PostMapping("/refresh")
    public Result<LoginResult> refreshToken(@RequestBody TokenRefreshRequest req) {
        return Result.ok(authService.refreshToken(req.getRefreshToken()));
    }

    /**
     * 修改密码（需登录）
     */
    @Operation(summary = "修改密码")
    @PostMapping("/change-password")
    public Result<Void> changePassword(Authentication authentication,
                                        @RequestBody @Validated ChangePasswordRequest req) {
        Long userId = (Long) authentication.getPrincipal();
        authService.changePassword(userId, req.getOldPassword(), req.getNewPassword());
        return Result.ok();
    }

    /**
     * 重置密码（公开，通过验证码）
     */
    @Operation(summary = "重置密码")
    @PostMapping("/reset-password")
    public Result<Void> resetPassword(@RequestBody @Validated ResetPasswordRequest req) {
        authService.resetPassword(req.getEmail(), req.getCode(), req.getNewPassword());
        return Result.ok();
    }

    /**
     * 登出（将 Token 加入黑名单）
     */
    @Operation(summary = "登出")
    @PostMapping("/logout")
    public Result<Void> logout(HttpServletRequest request) {
        String token = extractToken(request);
        authService.logout(token);
        return Result.ok();
    }

    /**
     * 从请求头中提取 Bearer Token
     * @param request HTTP 请求
     * @return Token 字符串，若无则返回 null
     */
    private String extractToken(HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");
        if (bearerToken != null && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        }
        return null;
    }

}
