package com.kbook.config;

import com.kbook.common.api.Result;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

    private final JwtUtil jwtUtil;
    private final ObjectMapper objectMapper;
    private final StringRedisTemplate redisTemplate;

    private static final String TOKEN_BLACKLIST_PREFIX = "token:blacklist:";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String token = extractToken(request);

        if (StringUtils.hasText(token) && jwtUtil.validateToken(token)) {
            // 检查黑名单（Redis 异常时放行，避免因 Redis 不可用导致全部 403）
            try {
                if (isTokenBlacklisted(token)) {
                    log.debug("Token 已在黑名单中: uri={}", request.getRequestURI());
                    sendErrorResponse(response, HttpServletResponse.SC_UNAUTHORIZED, "Token 已失效，请重新登录");
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
                log.debug("JWT 认证成功: userId={}, role={}, uri={}", userId, role, request.getRequestURI());
            } catch (Exception e) {
                log.warn("Token 解析失败: {} - {}", request.getRequestURI(), e.getMessage());
                sendErrorResponse(response, HttpServletResponse.SC_UNAUTHORIZED, "Token 无效或已过期");
                return;
            }
        } else if (StringUtils.hasText(token)) {
            log.debug("Token 验证失败: uri={}", request.getRequestURI());
        }

        filterChain.doFilter(request, response);
    }

    private boolean isTokenBlacklisted(String token) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(TOKEN_BLACKLIST_PREFIX + token));
    }

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

    private void sendErrorResponse(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(Result.fail(status, message)));
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
