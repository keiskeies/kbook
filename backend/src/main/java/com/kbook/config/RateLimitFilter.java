package com.kbook.config;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
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
import java.util.concurrent.ConcurrentHashMap;

/**
 * 限流过滤器 — 基于 Bucket4j 本地令牌桶
 * <p>
 * 策略：
 * - 登录/注册：5 请求/分钟/IP
 * - AI 对话：10 请求/分钟/用户
 * - 搜索：20 请求/分钟/IP
 */
@Slf4j
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    /** 本地 Bucket 缓存 */
    private final ConcurrentHashMap<String, Bucket> localBuckets = new ConcurrentHashMap<>();

    @Override
    protected void doFilterInternal(HttpServletRequest request, @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        String uri = request.getRequestURI();
        String clientIp = getClientIp(request);

        // 登录/注册限流：5次/分钟/IP
        if (uri.startsWith("/api/auth/login") || uri.startsWith("/api/auth/register")) {
            if (!tryConsume("auth:" + clientIp, 5, Duration.ofMinutes(1))) {
                sendTooManyRequests(response, "请求过于频繁，请1分钟后重试");
                return;
            }
        }

        // AI 对话限流：10次/分钟/用户
        if (uri.startsWith("/api/ai/")) {
            String userId = request.getHeader("Authorization") != null ? request.getHeader("Authorization").hashCode() + "" : clientIp;
            if (!tryConsume("ai:" + userId, 10, Duration.ofMinutes(1))) {
                sendTooManyRequests(response, "AI 对话请求过于频繁，请稍后重试");
                return;
            }
        }

        // 搜索限流：20次/分钟/IP
        if (uri.startsWith("/api/books/search") || uri.startsWith("/api/books/suggest")) {
            if (!tryConsume("search:" + clientIp, 20, Duration.ofMinutes(1))) {
                sendTooManyRequests(response, "搜索请求过于频繁，请稍后重试");
                return;
            }
        }

        filterChain.doFilter(request, response);
    }

    /**
     * 尝试消费令牌
     */
    private boolean tryConsume(String key, int capacity, Duration refillPeriod) {
        Bucket bucket = localBuckets.computeIfAbsent(key, k -> {
            Bandwidth bandwidth = Bandwidth.builder()
                    .capacity(capacity)
                    .refillIntervally(capacity, refillPeriod)
                    .build();
            return Bucket.builder()
                    .addLimit(bandwidth)
                    .build();
        });
        return bucket.tryConsume(1);
    }

    private void sendTooManyRequests(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(String.format("{\"code\":429,\"message\":\"%s\",\"data\":null}", message));
    }

    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isBlank()) {
            ip = request.getHeader("X-Real-IP");
        }
        if (ip == null || ip.isBlank()) {
            ip = request.getRemoteAddr();
        }
        // 多级代理取第一个
        if (ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        return ip;
    }
}
