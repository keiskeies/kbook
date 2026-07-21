package com.kbook.util;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Refresh Token Cookie 工具类
 * <p>
 * 用 HttpOnly Cookie 存储 refresh token，解决移动端浏览器（iOS Safari ITP、
 * Android Chrome）7 天不访问后强制清空 localStorage 导致用户被踢登录的问题。
 * <p>
 * 安全权衡：
 * - HttpOnly 防 XSS 偷取（JS 读不到）
 * - SameSite=Lax 缓解 CSRF（跨站 POST 不带 cookie）
 * - Secure 生产环境强制 HTTPS
 * - refresh token 只用于 /api/auth/refresh 一个接口，CSRF 攻击面极小
 */
@Component
public class RefreshTokenCookieUtil {

    public static final String COOKIE_NAME = "kbook_refresh_token";

    @Value("${jwt.refresh-token-expiration:2592000000}")
    private long refreshTokenExpirationMs;

    /**
     * 设置 refresh token 到 HttpOnly Cookie
     */
    public void setRefreshTokenCookie(HttpServletResponse response, String refreshToken) {
        Cookie cookie = new Cookie(COOKIE_NAME, refreshToken);
        cookie.setHttpOnly(true);
        cookie.setSecure(true);
        cookie.setPath("/");
        cookie.setMaxAge((int) (refreshTokenExpirationMs / 1000));
        cookie.setAttribute("SameSite", "Lax");
        response.addCookie(cookie);
    }

    /**
     * 从 Cookie 读取 refresh token
     */
    public String getRefreshTokenFromCookie(HttpServletRequest request) {
        if (request == null || request.getCookies() == null) return null;
        for (Cookie cookie : request.getCookies()) {
            if (COOKIE_NAME.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    /**
     * 清除 refresh token Cookie
     */
    public void clearRefreshTokenCookie(HttpServletResponse response) {
        Cookie cookie = new Cookie(COOKIE_NAME, "");
        cookie.setHttpOnly(true);
        cookie.setSecure(true);
        cookie.setPath("/");
        cookie.setMaxAge(0);
        cookie.setAttribute("SameSite", "Lax");
        response.addCookie(cookie);
    }
}
