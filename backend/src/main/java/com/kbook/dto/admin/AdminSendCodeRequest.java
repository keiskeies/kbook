package com.kbook.dto.admin;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 管理员发送验证码请求
 * 用于管理员为用户发送邮箱验证码
 */
@Data
public class AdminSendCodeRequest {
    /** 目标邮箱地址 */
    @Email(message = "邮箱格式不正确")
    @NotBlank(message = "邮箱不能为空")
    private String email;
}
