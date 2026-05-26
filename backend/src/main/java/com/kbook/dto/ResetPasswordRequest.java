package com.kbook.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 重置密码请求
 * 用于用户通过邮箱验证码重置登录密码
 */
@Data
public class ResetPasswordRequest {
    /** 邮箱地址 */
    @Email @NotBlank
    private String email;
    /** 邮箱验证码 */
    @NotBlank
    private String code;
    /** 新密码（6-20位） */
    @NotBlank @Size(min = 6, max = 20, message = "新密码长度应为6-20位")
    private String newPassword;
}
