package com.kbook.controller;

import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

/**
 * 控制器基类 — 提取公共方法
 */
public abstract class BaseController {

    /**
     * 从 Spring Security 上下文中提取当前登录用户 ID
     * 适用于未通过 @AuthenticationPrincipal 注入的接口
     */
    protected Long extractUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录");
        }
        Object principal = authentication.getPrincipal();
        if (principal instanceof Long userId) {
            return userId;
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "无法识别用户身份");
    }
}
