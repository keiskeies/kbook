package com.kbook.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;

/**
 * 限流过滤器 — 基于 Bucket4j + Caffeine 本地令牌桶
 * <p>
 * 策略：
 * - 登录/注册：5 请求/分钟/IP
 * - 验证码：10 请求/分钟/IP
 * - AI 对话：10 请求/分钟/用户
 * - 搜索：20 请求/分钟/IP
 * <p>
 * 使用 Caffeine 缓存以避免无界 ConcurrentHashMap 导致的 OOM。
 */
@Slf4j
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    /** 桶最大条目数（按 IP/用户维度） */
    private static final int MAX_BUCKETS = 50_000;
    /** 桶空闲过期时间 */
    private static final Duration BUCKET_IDLE_TTL = Duration.ofMinutes(10);

    /** 本地 Bucket 缓存（Caffeine，LRU + 读写过期） */
    private final Cache<String, Bucket> localBuckets = Caffeine.newBuilder()
            .maximumSize(MAX_BUCKETS)
            .expireAfterAccess(BUCKET_IDLE_TTL)
            .build();

    /**
     * 过滤器核心逻辑 — 根据请求路径和客户端标识进行令牌桶限流
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        String uri = request.getRequestURI();
        String clientIp = getClientIp(request);

        if (uri.startsWith("/api/auth/login") || uri.startsWith("/api/auth/register")) {
            if (!tryConsume("auth:" + clientIp, 5, Duration.ofMinutes(1))) {
                sendTooManyRequests(response, "请求过于频繁，请1分钟后重试");
                return;
            }
        }

        // 验证码生成：10 次/分钟/IP（防刷爆 Redis）
        if (uri.startsWith("/api/captcha/")) {
            if (!tryConsume("captcha:" + clientIp, 10, Duration.ofMinutes(1))) {
                sendTooManyRequests(response, "验证码请求过于频繁，请稍后重试");
                return;
            }
        }

        // 邮件验证码发送：5 次/分钟/IP（防止邮件轰炸，service 层另有 email 维度限频）
        if (uri.startsWith("/api/auth/send-code")) {
            if (!tryConsume("sendcode:" + clientIp, 5, Duration.ofMinutes(1))) {
                sendTooManyRequests(response, "验证码发送过于频繁，请稍后重试");
                return;
            }
        }

        if (uri.startsWith("/api/ai/chat/")
                || uri.startsWith("/api/books/") && (uri.endsWith("/chat/stream") || uri.endsWith("/chat/suggestions"))) {
            String userKey = resolveAiUserKey(request, clientIp);
            if (!tryConsume("ai:" + userKey, 10, Duration.ofMinutes(1))) {
                sendTooManyRequests(response, "AI 对话请求过于频繁，请稍后重试");
                return;
            }
        }

        if (uri.startsWith("/api/books/search") || uri.startsWith("/api/books/suggest")) {
            if (!tryConsume("search:" + clientIp, 20, Duration.ofMinutes(1))) {
                sendTooManyRequests(response, "搜索请求过于频繁，请稍后重试");
                return;
            }
        }

        filterChain.doFilter(request, response);
    }

    /**
     * 解析 AI 对话限流维度。
     * 已认证请求使用 userId（来自 SecurityContext），匿名请求回退到 IP。
     */
    private String resolveAiUserKey(HttpServletRequest request, String clientIp) {
        var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && auth.getPrincipal() instanceof Long userId) {
            return "u" + userId;
        }
        return "ip:" + clientIp;
    }

    /**
     * 尝试消费一个令牌
     */
    private boolean tryConsume(String key, int capacity, Duration refillPeriod) {
        Bucket bucket = localBuckets.get(key, k -> {
            Bandwidth bandwidth = Bandwidth.builder()
                    .capacity(capacity)
                    .refillIntervally(capacity, refillPeriod)
                    .build();
            return Bucket.builder().addLimit(bandwidth).build();
        });
        if (bucket == null) {
            return true;
        }
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        return probe.isConsumed();
    }

    /**
     * 发送 429 Too Many Requests 响应
     */
    private void sendTooManyRequests(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(String.format("{\"code\":429,\"message\":\"%s\",\"data\":null}", message));
    }

    /**
     * 获取客户端真实 IP 地址
     * <p>
     * 安全策略：仅在请求来自可信代理（内网/nginx）时才信任 X-Forwarded-For / X-Real-IP。
     * 否则直接使用 TCP 连接的 RemoteAddr，防止攻击者伪造 XFF 绕过限流。
     */
    private String getClientIp(HttpServletRequest request) {
        String remoteAddr = request.getRemoteAddr();

        // 仅信任可信代理转发的 IP 头
        if (isTrustedProxy(remoteAddr)) {
            String xff = request.getHeader("X-Forwarded-For");
            if (xff != null && !xff.isBlank() && !"unknown".equalsIgnoreCase(xff)) {
                // XFF 可能是链式："客户端IP, 代理1, 代理2"，取第一个（最原始的客户端）
                return xff.split(",")[0].trim();
            }
            String xri = request.getHeader("X-Real-IP");
            if (xri != null && !xri.isBlank() && !"unknown".equalsIgnoreCase(xri)) {
                return xri.trim();
            }
        }

        return remoteAddr;
    }

    /**
     * 判断 IP 是否为可信代理（内网地址）
     * <p>
     * 包括：127.0.0.1（本地）、10.x.x.x（A 类私有）、172.16-31.x.x（B 类私有，含 Docker 网桥 172.17.x.x）、
     * 192.168.x.x（C 类私有）、::1（IPv6 本地回环）
     */
    private static boolean isTrustedProxy(String ip) {
        if (ip == null || ip.isBlank()) return false;
        if ("127.0.0.1".equals(ip) || "::1".equals(ip) || "0:0:0:0:0:0:0:1".equals(ip)) return true;
        try {
            String[] parts = ip.split("\\.");
            if (parts.length != 4) return false;
            int a = Integer.parseInt(parts[0]);
            int b = Integer.parseInt(parts[1]);
            if (a == 10) return true;                       // 10.0.0.0/8
            if (a == 172 && b >= 16 && b <= 31) return true; // 172.16.0.0/12
            if (a == 192 && b == 168) return true;           // 192.168.0.0/16
            return false;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
