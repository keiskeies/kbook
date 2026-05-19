package com.kbook.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 验证码登录请求
 * 使用邮箱+验证码进行登录
 */
@Data
public class CodeLoginRequest {
    /** 邮箱地址 */
    @Email 
    @NotBlank
    private String email;
    
    /** 邮箱验证码 */
    @NotBlank
    private String code;
}
