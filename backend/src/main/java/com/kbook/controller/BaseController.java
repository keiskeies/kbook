package com.kbook.controller;

import com.kbook.config.SseConnectionLimiter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.function.Supplier;

/**
 * 控制器基类 — 提取公共方法
 */
public abstract class BaseController {

    @Autowired
    protected SseConnectionLimiter sseLimiter;

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

    /**
     * 带限流的 SSE 创建器 — 所有 SSE 端点必须通过此方法包装
     * <p>
     * 全局上限 50、用户上限 3，超出时返回带错误事件的 emitter。
     *
     * @param userId   用户 ID
     * @param supplier SSE emitter 创建逻辑
     * @return SseEmitter 实例
     */
    protected SseEmitter withSseLimit(Long userId, Supplier<SseEmitter> supplier) {
        return sseLimiter.withLimit(userId, supplier);
    }
}
