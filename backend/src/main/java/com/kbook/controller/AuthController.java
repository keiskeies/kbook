package com.kbook.controller;

import com.kbook.common.api.Result;
import com.kbook.dto.auth.AuthSendCodeRequest;
import com.kbook.dto.auth.ChangePasswordRequest;
import com.kbook.dto.auth.CodeLoginRequest;
import com.kbook.dto.auth.LoginResult;
import com.kbook.dto.auth.PasswordLoginRequest;
import com.kbook.dto.auth.RegisterRequest;
import com.kbook.dto.auth.ResetPasswordRequest;
import com.kbook.dto.auth.TokenRefreshRequest;
import com.kbook.service.auth.AuthService;
import com.kbook.util.RefreshTokenCookieUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Validated
@Tag(name = "认证")
public class AuthController {

    private final AuthService authService;
    private final RefreshTokenCookieUtil refreshTokenCookieUtil;

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
    public Result<LoginResult> loginByCode(@RequestBody @Validated CodeLoginRequest req,
                                            HttpServletRequest request,
                                            HttpServletResponse response) {
        LoginResult result = authService.loginByCode(req.getEmail(), req.getCode());
        refreshTokenCookieUtil.setRefreshTokenCookie(request, response, result.getRefreshToken());
        return Result.ok(result);
    }

    /**
     * 密码登录
     */
    @Operation(summary = "密码登录")
    @PostMapping("/login/password")
    public Result<LoginResult> loginByPassword(@RequestBody @Validated PasswordLoginRequest req,
                                                HttpServletRequest request,
                                                HttpServletResponse response) {
        LoginResult result = authService.loginByPassword(req.getEmail(), req.getPassword(), req.getCaptchaId());
        refreshTokenCookieUtil.setRefreshTokenCookie(request, response, result.getRefreshToken());
        return Result.ok(result);
    }

    /**
     * 注册
     */
    @Operation(summary = "注册")
    @PostMapping("/register")
    public Result<LoginResult> register(@RequestBody @Validated RegisterRequest req,
                                         HttpServletRequest request,
                                         HttpServletResponse response) {
        LoginResult result = authService.register(
                req.getEmail(), req.getCode(), req.getPassword(),
                req.getBirthday(), req.getGender(), req.getMarried(), req.getHasChildren(), req.getMbti());
        refreshTokenCookieUtil.setRefreshTokenCookie(request, response, result.getRefreshToken());
        return Result.ok(result);
    }

    /**
     * 刷新 Token
     * <p>
     * 优先从 HttpOnly Cookie 读取 refresh token（移动端友好，不受 ITP 清理影响），
     * 兼容回退到 body 传参（老前端 / 第三方调用）。
     */
    @Operation(summary = "刷新Token")
    @PostMapping("/refresh")
    public Result<LoginResult> refreshToken(HttpServletRequest request,
                                             HttpServletResponse response,
                                             @RequestBody(required = false) TokenRefreshRequest req) {
        // 优先从 Cookie 读取
        String refreshToken = refreshTokenCookieUtil.getRefreshTokenFromCookie(request);
        String source = "cookie";
        // 回退到 body（兼容老前端）
        if (refreshToken == null && req != null) {
            refreshToken = req.getRefreshToken();
            source = "body";
        }
        if (refreshToken == null) {
            log.warn("刷新失败：未提供 refresh token（cookie 和 body 都没有）");
            return Result.fail(1001, "Refresh Token 已失效");
        }
        try {
            LoginResult result = authService.refreshToken(refreshToken);
            // 刷新成功后更新 Cookie（新签发的 refresh token）
            refreshTokenCookieUtil.setRefreshTokenCookie(request, response, result.getRefreshToken());
            log.debug("刷新成功：来源={}, userId={}", source, result.getUserInfo() != null ? result.getUserInfo().getId() : "unknown");
            return Result.ok(result);
        } catch (Exception e) {
            log.warn("刷新失败：来源={}, error={}", source, e.getMessage());
            // 刷新失败时也清 cookie，避免浏览器持续用已失效的 cookie
            refreshTokenCookieUtil.clearRefreshTokenCookie(request, response);
            throw e;
        }
    }

    /**
     * 登出
     * <p>
     * 清除 refresh token Cookie + 拉黑当前 access token。
     * 需要登录态（access token 未过期）。
     */
    @Operation(summary = "登出")
    @PostMapping("/logout")
    public Result<Void> logout(HttpServletRequest request,
                                HttpServletResponse response) {
        // 从 Authorization 头取 access token 并拉黑
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            authService.logout(token);
        }
        // 清除 refresh token Cookie
        refreshTokenCookieUtil.clearRefreshTokenCookie(request, response);
        return Result.ok();
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
}
