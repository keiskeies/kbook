package com.kbook.controller;

import com.kbook.common.api.Result;
import com.kbook.dto.LoginResult;
import com.kbook.service.AuthService;
import com.kbook.dto.AuthSendCodeRequest;
import com.kbook.dto.ChangePasswordRequest;
import com.kbook.dto.CodeLoginRequest;
import com.kbook.dto.PasswordLoginRequest;
import com.kbook.dto.RegisterRequest;
import com.kbook.dto.ResetPasswordRequest;
import com.kbook.dto.TokenRefreshRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * 认证控制器
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Validated
public class AuthController {

    private final AuthService authService;

    /**
     * 发送验证码
     * @param req 场景：register | login | reset
     */
    @PostMapping("/send-code")
    public Result<Void> sendCode(@RequestBody @Validated AuthSendCodeRequest req) {
        authService.sendVerificationCode(req.getEmail(), req.getScene(), req.getCaptchaId());
        return Result.ok();
    }

    /**
     * 验证码登录
     */
    @PostMapping("/login/code")
    public Result<LoginResult> loginByCode(@RequestBody @Validated CodeLoginRequest req) {
        return Result.ok(authService.loginByCode(req.getEmail(), req.getCode()));
    }

    /**
     * 密码登录
     */
    @PostMapping("/login/password")
    public Result<LoginResult> loginByPassword(@RequestBody @Validated PasswordLoginRequest req) {
        return Result.ok(authService.loginByPassword(req.getEmail(), req.getPassword(), req.getCaptchaId()));
    }

    /**
     * 注册
     */
    @PostMapping("/register")
    public Result<LoginResult> register(@RequestBody @Validated RegisterRequest req) {
        return Result.ok(authService.register(
                req.getEmail(), req.getCode(), req.getPassword(),
                req.getBirthday(), req.getGender(), req.getMarried(), req.getHasChildren(), req.getMbti()));
    }

    /**
     * 刷新 Token
     */
    @PostMapping("/refresh")
    public Result<LoginResult> refreshToken(@RequestBody TokenRefreshRequest req) {
        return Result.ok(authService.refreshToken(req.getRefreshToken()));
    }

    /**
     * 修改密码（需登录）
     */
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
    @PostMapping("/reset-password")
    public Result<Void> resetPassword(@RequestBody @Validated ResetPasswordRequest req) {
        authService.resetPassword(req.getEmail(), req.getCode(), req.getNewPassword());
        return Result.ok();
    }

    /**
     * 登出（将 Token 加入黑名单）
     */
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
