package com.kbook.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * 请求安全过滤器 — 防护路径绕过 + CSRF + 全角 Unicode 注入
 * <p>
 * 安全措施：
 * 1. P2 #11：拒绝包含全角 Unicode 斜杠（U+FF0F）的请求路径，防止绕过 Spring Security 路径匹配
 * 2. P2 #10：拒绝大小写不匹配的 /api/admin/ 路径，防止通过 /api/Admin/ 绕过权限检查
 * 3. P1 #8：对状态变更方法（POST/PUT/DELETE/PATCH）校验 Origin/Referer 头，防止 CSRF
 */
@Slf4j
@Component
public class RequestSecurityFilter extends OncePerRequestFilter {

    /** 全角斜杠 U+FF0F */
    private static final char FULLWIDTH_SLASH = '\uFF0F';

    /** 状态变更方法集合 — 需要 CSRF 校验 */
    private static final Set<String> STATE_CHANGING_METHODS = Set.of(
            HttpMethod.POST.name(), HttpMethod.PUT.name(),
            HttpMethod.DELETE.name(), HttpMethod.PATCH.name()
    );

    /** 需要严格小写校验的管理员路径前缀 */
    private static final String ADMIN_PATH_PREFIX = "/api/admin/";

    /** 允许的 Origin 模式（与 CorsConfig 共享配置） */
    @Value("${kbook.cors.allowed-origin-patterns:http://localhost,http://localhost:*,http://127.0.0.1,http://127.0.0.1:*}")
    private String[] allowedOriginPatterns;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        String uri = request.getRequestURI();

        // 1. P2 #11：拒绝包含全角 Unicode 斜杠的请求路径
        if (uri.indexOf(FULLWIDTH_SLASH) >= 0) {
            log.warn("拒绝包含全角斜杠的请求: {}", uri);
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":400,\"message\":\"请求路径包含非法字符\",\"data\":null}");
            return;
        }

        // 2. P2 #10：拒绝大小写不匹配的管理员路径
        // 合法的 /api/admin/ 路径必须全小写，/api/Admin/ 等大写变体视为绕过尝试
        if (uri.length() >= ADMIN_PATH_PREFIX.length()) {
            String prefix = uri.substring(0, ADMIN_PATH_PREFIX.length());
            if (prefix.equalsIgnoreCase(ADMIN_PATH_PREFIX) && !prefix.equals(ADMIN_PATH_PREFIX)) {
                log.warn("拒绝大小写绕过的管理员路径: {}", uri);
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"code\":403,\"message\":\"权限不足，无法访问此资源\",\"data\":null}");
                return;
            }
        }

        // 3. P1 #8：对状态变更方法校验 Origin/Referer（CSRF 防护）
        if (STATE_CHANGING_METHODS.contains(request.getMethod())) {
            String origin = request.getHeader("Origin");
            String referer = request.getHeader("Referer");

            log.debug("CSRF 校验: {} {} Origin=[{}] Referer=[{}] patterns={}",
                    request.getMethod(), uri, origin, referer, Arrays.toString(allowedOriginPatterns));

            // 如果 Origin 头存在，校验是否在白名单中
            if (origin != null && !origin.isBlank()) {
                if (!isOriginAllowed(origin)) {
                    log.warn("CSRF 防护：拒绝非法 Origin: {} {} origin={}", request.getMethod(), uri, origin);
                    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                    response.setContentType("application/json;charset=UTF-8");
                    response.getWriter().write("{\"code\":403,\"message\":\"跨域请求被拒绝\",\"data\":null}");
                    return;
                }
            } else if (referer != null && !referer.isBlank()) {
                // 没有 Origin 头时，校验 Referer 头的 origin 部分
                String refererOrigin = extractOrigin(referer);
                if (refererOrigin != null && !isOriginAllowed(refererOrigin)) {
                    log.warn("CSRF 防护：拒绝非法 Referer: {} {} referer={}", request.getMethod(), uri, referer);
                    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                    response.setContentType("application/json;charset=UTF-8");
                    response.getWriter().write("{\"code\":403,\"message\":\"跨域请求被拒绝\",\"data\":null}");
                    return;
                }
            }
            // 如果 Origin 和 Referer 都不存在，允许请求通过（非浏览器客户端如 curl）
        }

        filterChain.doFilter(request, response);
    }

    /**
     * 检查 Origin 是否在允许的白名单中
     * 使用 Spring 的 AntPathMatcher 风格的模式匹配
     */
    private boolean isOriginAllowed(String origin) {
        if (origin == null || origin.isBlank()) {
            return false;
        }
        String normalizedOrigin = origin.trim();
        List<String> patterns = Arrays.stream(allowedOriginPatterns)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();

        for (String pattern : patterns) {
            if (matchesPattern(normalizedOrigin, pattern)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 简单的模式匹配：支持 * 通配符
     * 例如 "http://localhost:*" 匹配 "http://localhost:8080"
     * 特殊处理：pattern 以 ":*" 结尾时，同时匹配不带端口的形式（浏览器对 80/443 默认端口会省略）
     */
    private boolean matchesPattern(String origin, String pattern) {
        if (pattern.equals(origin)) {
            return true;
        }
        // 特殊处理：pattern 以 ":*" 结尾时，同时匹配不带端口的形式
        // 例如 "http://localhost:*" 同时匹配 "http://localhost" 和 "http://localhost:8080"
        if (pattern.endsWith(":*")) {
            String basePattern = pattern.substring(0, pattern.length() - 2);
            if (basePattern.equals(origin)) {
                return true;
            }
        }
        // 将 * 转为正则通配
        String regex = pattern.replace(".", "\\.").replace("*", ".*");
        return origin.matches(regex);
    }

    /**
     * 从 Referer URL 中提取 Origin（scheme://host[:port]）
     */
    private String extractOrigin(String referer) {
        try {
            URI uri = new URI(referer);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            int port = uri.getPort();
            if (scheme == null || host == null) {
                return null;
            }
            if (port > 0) {
                return scheme + "://" + host + ":" + port;
            }
            return scheme + "://" + host;
        } catch (URISyntaxException e) {
            return null;
        }
    }
}
