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
 * - Secure 根据请求 scheme 动态设置（HTTPS=treu, HTTP=false），兼容 dev 和 prod
 * - refresh token 只用于 /api/auth/refresh 一个接口，CSRF 攻击面极小
 */
@Component
public class RefreshTokenCookieUtil {

    public static final String COOKIE_NAME = "kbook_refresh_token";

    @Value("${jwt.refresh-token-expiration:2592000000}")
    private long refreshTokenExpirationMs;

    /**
     * 判断请求是否为 HTTPS（支持反向代理）
     * <p>
     * 优先检查 X-Forwarded-Proto 头（Nginx/负载均衡器常用），
     * 回退到 request.isSecure()（Servlet 容器原生判断）。
     */
    private boolean isSecureRequest(HttpServletRequest request) {
        if (request == null) return true; // 默认安全
        String forwardedProto = request.getHeader("X-Forwarded-Proto");
        if (forwardedProto != null) {
            return "https".equalsIgnoreCase(forwardedProto);
        }
        return request.isSecure();
    }

    /**
     * 设置 refresh token 到 HttpOnly Cookie
     */
    public void setRefreshTokenCookie(HttpServletRequest request, HttpServletResponse response, String refreshToken) {
        boolean secure = isSecureRequest(request);
        Cookie cookie = new Cookie(COOKIE_NAME, refreshToken);
        cookie.setHttpOnly(true);
        cookie.setSecure(secure);
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
    public void clearRefreshTokenCookie(HttpServletRequest request, HttpServletResponse response) {
        boolean secure = isSecureRequest(request);
        Cookie cookie = new Cookie(COOKIE_NAME, "");
        cookie.setHttpOnly(true);
        cookie.setSecure(secure);
        cookie.setPath("/");
        cookie.setMaxAge(0);
        cookie.setAttribute("SameSite", "Lax");
        response.addCookie(cookie);
    }
}
