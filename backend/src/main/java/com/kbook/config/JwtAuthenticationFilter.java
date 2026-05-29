package com.kbook.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kbook.common.api.Result;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * JWT 认证过滤器 - 拦截请求校验 Token + 黑名单检查
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    /** JWT 工具类 */
    private final JwtUtil jwtUtil;
    /** JSON 序列化工具 */
    private final ObjectMapper objectMapper;
    /** Redis 模板（用于 Token 黑名单检查） */
    private final StringRedisTemplate redisTemplate;

    /** Token 黑名单 Redis Key 前缀 */
    private static final String TOKEN_BLACKLIST_PREFIX = "token:blacklist:";

    /**
     * 过滤器核心逻辑 — 提取并校验 JWT Token，设置安全上下文
     */
    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        String token = extractToken(request);

        if (StringUtils.hasText(token) && jwtUtil.validateToken(token)) {
            // 检查黑名单（Redis 异常时放行，避免因 Redis 不可用导致全部 403）
            try {
                if (isTokenBlacklisted(token)) {
                    log.debug("Token 已在黑名单中: uri={}", request.getRequestURI());
                    sendErrorResponse(response, "Token 已失效，请重新登录");
                    return;
                }
            } catch (Exception e) {
                log.warn("Redis 黑名单检查失败，放行请求: {}", e.getMessage());
            }

            try {
                Long userId = jwtUtil.getUserId(token);
                String role = jwtUtil.getRole(token);

                UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(
                                userId,
                                null,
                                List.of(new SimpleGrantedAuthority("ROLE_" + role))
                        );
                SecurityContextHolder.getContext().setAuthentication(authentication);
//                log.debug("JWT 认证成功: userId={}, role={}, uri={}", userId, role, request.getRequestURI());
            } catch (Exception e) {
                log.warn("Token 解析失败: {} - {}", request.getRequestURI(), e.getMessage());
                sendErrorResponse(response, "Token 无效或已过期");
                return;
            }
        } else if (StringUtils.hasText(token)) {
            log.debug("Token 验证失败: uri={}", request.getRequestURI());
        }

        filterChain.doFilter(request, response);
    }

    /**
     * 检查 Token 是否在黑名单中（用户已登出）
     *
     * @param token JWT Token
     * @return true-已黑名单, false-未黑名单
     */
    private boolean isTokenBlacklisted(String token) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(TOKEN_BLACKLIST_PREFIX + token));
    }

    /**
     * 从请求中提取 JWT Token
     * <p>
     * 优先从 Authorization 头提取，其次从 query 参数提取（用于浏览器可缓存的文件请求）
     *
     * @param request HTTP 请求
     * @return Token 字符串，不存在则返回 null
     */
    private String extractToken(HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        }
        // 支持 query 参数传递 token（用于浏览器可缓存的文件请求）
        String queryToken = request.getParameter("token");
        if (StringUtils.hasText(queryToken)) {
            return queryToken;
        }
        return null;
    }

    /**
     * 发送 401 未认证错误响应
     *
     * @param response HTTP 响应
     * @param message  错误信息
     */
    private void sendErrorResponse(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(Result.fail(HttpServletResponse.SC_UNAUTHORIZED, message)));
    }

    /**
     * 不拦截的路径（登录、注册、公开接口）
     * 注意：/api/auth/change-password 和 /api/auth/logout 需要认证，不应跳过
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.equals("/api/auth/send-code") ||
                path.startsWith("/api/auth/login/") ||
                path.equals("/api/auth/register") ||
                path.equals("/api/auth/refresh") ||
                path.equals("/api/auth/reset-password") ||
                path.startsWith("/api/public/") ||
                path.equals("/api/health") ||
                path.startsWith("/swagger") ||
                path.startsWith("/actuator");
    }
}
