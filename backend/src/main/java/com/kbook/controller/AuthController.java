package com.kbook.controller;

import com.kbook.common.api.Result;
import com.kbook.service.AuthService;
import com.kbook.service.AuthService.LoginResult;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

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
     * @param scene 场景：register | login | reset
     * @param captchaId 滑动验证码ID
     */
    @PostMapping("/send-code")
    public Result<Void> sendCode(@RequestBody @Validated SendCodeRequest req) {
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
    public Result<LoginResult> refreshToken(@RequestBody RefreshRequest req) {
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

    private String extractToken(HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");
        if (bearerToken != null && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        }
        return null;
    }

    // ========== 请求体 ==========

    @Data
    public static class SendCodeRequest {
        @Email(message = "邮箱格式不正确")
        @NotBlank(message = "邮箱不能为空")
        private String email;

        /** 场景：register | login | reset */
        @NotBlank(message = "场景不能为空")
        private String scene;

        /** 点击验证码ID */
        @NotBlank(message = "请先完成验证")
        private String captchaId;
    }

    @Data
    public static class CodeLoginRequest {
        @Email @NotBlank
        private String email;
        @NotBlank
        private String code;
    }

    @Data
    public static class PasswordLoginRequest {
        @Email @NotBlank
        private String email;
        @NotBlank
        private String password;

        /** 点击验证码ID */
        @NotBlank(message = "请先完成验证")
        private String captchaId;
    }

    @Data
    public static class RegisterRequest {
        @Email @NotBlank
        private String email;
        @NotBlank
        private String code;
        @NotBlank @Size(min = 6, max = 20, message = "密码长度应为6-20位")
        private String password;
        /** 出生日期（可选） */
        private LocalDate birthday;
        /** 性别：MALE / FEMALE / OTHER（可选） */
        private String gender;
        /** 是否已婚（可选） */
        private Boolean married;
        /** 是否有孩子（可选） */
        private Boolean hasChildren;
        /** MBTI 人格类型（可选） */
        private String mbti;
    }

    @Data
    public static class RefreshRequest {
        @NotBlank
        private String refreshToken;
    }

    @Data
    public static class ChangePasswordRequest {
        @NotBlank
        private String oldPassword;
        @NotBlank @Size(min = 6, max = 20, message = "新密码长度应为6-20位")
        private String newPassword;
    }

    @Data
    public static class ResetPasswordRequest {
        @Email @NotBlank
        private String email;
        @NotBlank
        private String code;
        @NotBlank @Size(min = 6, max = 20, message = "新密码长度应为6-20位")
        private String newPassword;
    }
}
