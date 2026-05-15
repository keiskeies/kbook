package com.kbook.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.SocketException;

/**
 * 安全响应头过滤器 — 添加生产环境安全相关 HTTP 头
 */
@Slf4j
@Component
public class SecurityHeaderFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        // 防止 MIME 类型嗅探
        response.setHeader("X-Content-Type-Options", "nosniff");
        // 防止点击劫持
        response.setHeader("X-Frame-Options", "DENY");
        // XSS 防护
        response.setHeader("X-XSS-Protection", "1; mode=block");
        // 引用来源策略
        response.setHeader("Referrer-Policy", "strict-origin-when-cross-origin");
        // 内容安全策略（API 服务宽松设置）
        response.setHeader("Content-Security-Policy", "default-src 'self'");
        // HSTS（仅 HTTPS 时生效）
        if (request.isSecure()) {
            response.setHeader("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
        }
        // 隐藏服务器信息
        response.setHeader("Server", "KBook");

        try {
            filterChain.doFilter(request, response);
        } catch (IOException e) {
            // 处理客户端断开连接的异常情况
            if (isClientDisconnected(e)) {
                log.debug("客户端连接已断开: {} {}", request.getMethod(), request.getRequestURI());
            } else {
                log.error("IO 异常: {} {} - {}", request.getMethod(), request.getRequestURI(), e.getMessage());
                throw e;
            }
        }
    }

    /**
     * 判断是否为客户端断开连接的异常
     */
    private boolean isClientDisconnected(IOException e) {
        String message = e.getMessage();
        return message != null && (
            message.contains("Connection reset by peer") ||
            message.contains("Broken pipe") ||
            message.contains("Connection timed out") ||
            message.contains("Software caused connection abort") ||
            e.getCause() instanceof SocketException
        );
    }
}
