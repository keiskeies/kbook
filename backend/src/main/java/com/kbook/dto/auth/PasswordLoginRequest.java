package com.kbook.dto.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 密码登录请求
 * 使用邮箱+密码进行登录，需要完成图片验证码验证
 */
@Data
public class PasswordLoginRequest {
    /** 邮箱地址 */
    @Email 
    @NotBlank
    private String email;
    
    /** 登录密码 */
    @NotBlank
    private String password;

    /** 验证码ID（点击图片验证码后返回） */
    @NotBlank(message = "请先完成验证")
    private String captchaId;
}
