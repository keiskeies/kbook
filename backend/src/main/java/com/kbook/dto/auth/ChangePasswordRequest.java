package com.kbook.dto.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 修改密码请求
 * 用于用户修改登录密码
 */
@Data
public class ChangePasswordRequest {
    /** 旧密码（当前密码） */
    @NotBlank
    private String oldPassword;
    
    /** 新密码（6-20位） */
    @NotBlank 
    @Size(min = 6, max = 20, message = "新密码长度应为6-20位")
    private String newPassword;
}
