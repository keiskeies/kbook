package com.kbook.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 认证发送验证码请求
 * 用于用户注册、登录、绑定邮箱等场景发送验证码
 */
@Data
public class AuthSendCodeRequest {
    /** 邮箱地址 */
    @Email(message = "邮箱格式不正确")
    @NotBlank(message = "邮箱不能为空")
    private String email;

    /** 使用场景：register-注册, login-登录, bind-绑定, reset-重置密码 */
    @NotBlank(message = "场景不能为空")
    private String scene;

    /** 验证码ID（点击图片验证码后返回） */
    @NotBlank(message = "请先完成验证")
    private String captchaId;
}
