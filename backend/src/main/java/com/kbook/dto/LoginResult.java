package com.kbook.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 登录结果
 * 包含访问令牌、刷新令牌和用户信息
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoginResult {
    /** JWT访问令牌（有效期2小时） */
    private String token;
    
    /** 刷新令牌（有效期7天） */
    private String refreshToken;
    
    /** 用户信息 */
    private UserInfo userInfo;
}
