package com.kbook.dto.auth;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 令牌刷新请求
 * 用于使用刷新令牌获取新的访问令牌
 */
@Data
public class TokenRefreshRequest {
    /** 刷新令牌 */
    @NotBlank
    private String refreshToken;
}
