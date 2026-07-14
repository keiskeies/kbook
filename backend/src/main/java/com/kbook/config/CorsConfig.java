package com.kbook.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 跨域配置 — 严格白名单模式，禁止 Origin 反射。
 * <p>
 * 配置项：
 * - kbook.cors.allowed-origin-patterns : 允许的 origin 模式列表（逗号分隔）
 * <p>
 * 安全要求：
 * 1. 不允许使用 http://*:* / https://*:* 等通配符模式（等同 Origin 反射）
 * 2. 生产环境必须显式配置为具体域名，例如：
 *    kbook.cors.allowed-origin-patterns=https://book.keiskei.top,https://www.book.keiskei.top
 * 3. 默认仅允许本机开发访问
 */
@Configuration
public class CorsConfig {

    /**
     * 默认的 origin 模式列表 — 仅允许本机开发访问（生产必须覆盖此配置）。
     * 注意：禁止使用 http://*:* 等通配符模式，否则会导致 Origin 反射漏洞。
     */
    private static final String DEFAULT_PATTERNS =
            "http://localhost," +
            "http://localhost:*," +
            "http://127.0.0.1," +
            "http://127.0.0.1:*";

    /** 允许的 origin 模式（逗号分隔） */
    @Value("${kbook.cors.allowed-origin-patterns:" + DEFAULT_PATTERNS + "}")
    private String[] allowedOriginPatterns;

    /**
     * 配置跨域过滤器
     */
    @Bean
    public CorsFilter corsFilter() {
        CorsConfiguration config = new CorsConfiguration();

        List<String> patterns = Arrays.stream(allowedOriginPatterns)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());

        // 安全检查：拒绝危险的通配符模式
        for (String pattern : patterns) {
            if (isUnsafeWildcardPattern(pattern)) {
                throw new IllegalStateException(
                    "检测到危险的 CORS 通配符模式: " + pattern + "\n" +
                    "此模式等同于 Origin 反射，会导致严重安全漏洞（CVSS 9.1）。\n" +
                    "请通过 kbook.cors.allowed-origin-patterns 显式配置信任的域名，\n" +
                    "例如: https://book.keiskei.top");
            }
        }

        if (patterns.isEmpty()) {
            throw new IllegalStateException("CORS allowed-origin-patterns 不能为空");
        }

        // 自动展开：对 "host:*" 模式额外添加 "host"（不带端口），
        // 因为浏览器对 80/443 默认端口会省略端口，Origin 头中不包含 ":"
        List<String> expandedPatterns = new ArrayList<>();
        for (String pattern : patterns) {
            expandedPatterns.add(pattern);
            if (pattern.endsWith(":*")) {
                expandedPatterns.add(pattern.substring(0, pattern.length() - 2));
            }
        }

        config.setAllowedOriginPatterns(expandedPatterns);

        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Requested-With"));
        config.setAllowCredentials(true);
        config.setExposedHeaders(List.of("Authorization", "Content-Disposition"));
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);

        return new CorsFilter(source);
    }

    /**
     * 判断是否为危险的通配符模式：
     * - "http://*:*" / "https://*:*" — 匹配任意 host+port，等同 Origin 反射
     * - "http://*" / "https://*" — 同上
     * - "*" — 允许所有 Origin（与 credentials=true 不兼容，Spring 会拒绝）
     */
    private boolean isUnsafeWildcardPattern(String pattern) {
        if (pattern == null) return false;
        String p = pattern.trim();
        return "*".equals(p)
                || "http://*".equals(p)
                || "https://*".equals(p)
                || "http://*:*".equals(p)
                || "https://*:*".equals(p);
    }
}
