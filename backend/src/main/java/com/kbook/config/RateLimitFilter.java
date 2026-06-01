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

        if (uri.startsWith("/api/ai/")) {
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
     */
    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isBlank()) {
            ip = request.getHeader("X-Real-IP");
        }
        if (ip == null || ip.isBlank()) {
            ip = request.getRemoteAddr();
        }
        if (ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        return ip;
    }
}
