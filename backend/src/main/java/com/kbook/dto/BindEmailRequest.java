package com.kbook.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 绑定邮箱请求
 * 用于用户绑定或修改邮箱地址
 */
@Data
public class BindEmailRequest {
    /** 要绑定的邮箱地址 */
    @Email(message = "邮箱格式不正确")
    @NotBlank(message = "邮箱不能为空")
    private String email;

    /** 邮箱验证码 */
    @NotBlank(message = "验证码不能为空")
    private String code;
}
