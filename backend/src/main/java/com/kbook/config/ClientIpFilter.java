package com.kbook.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 客户端 IP 过滤器 — 所有请求都记录真实 IP 到 MDC
 * <p>
 * 优先级最高（Ordered.HIGHEST_PRECEDENCE），确保在其他过滤器之前执行，
 * 即使 JwtAuthenticationFilter 被 shouldNotFilter 跳过，IP 也能被记录。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ClientIpFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        MDC.put("clientIp", getClientIp(request));
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove("clientIp");
        }
    }

    private String getClientIp(HttpServletRequest request) {
        String remoteAddr = request.getRemoteAddr();

        // 仅信任可信代理转发的 IP 头（防止伪造 XFF）
        if (isTrustedProxy(remoteAddr)) {
            String xff = request.getHeader("X-Forwarded-For");
            if (StringUtils.hasText(xff) && !"unknown".equalsIgnoreCase(xff)) {
                return xff.split(",")[0].trim();
            }
            String xri = request.getHeader("X-Real-IP");
            if (StringUtils.hasText(xri) && !"unknown".equalsIgnoreCase(xri)) {
                return xri.trim();
            }
        }

        return remoteAddr;
    }

    /**
     * 判断 IP 是否为可信代理（内网地址）
     */
    private static boolean isTrustedProxy(String ip) {
        if (ip == null || ip.isBlank()) return false;
        if ("127.0.0.1".equals(ip) || "::1".equals(ip) || "0:0:0:0:0:0:0:1".equals(ip)) return true;
        try {
            String[] parts = ip.split("\\.");
            if (parts.length != 4) return false;
            int a = Integer.parseInt(parts[0]);
            int b = Integer.parseInt(parts[1]);
            if (a == 10) return true;
            if (a == 172 && b >= 16 && b <= 31) return true;
            if (a == 192 && b == 168) return true;
            return false;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
