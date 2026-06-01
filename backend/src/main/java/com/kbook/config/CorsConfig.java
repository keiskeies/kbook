package com.kbook.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 跨域配置 — 通过 setAllowedOriginPatterns 允许通配符模式（可与 credentials 共存）。
 * <p>
 * 配置项：
 * - kbook.cors.allowed-origin-patterns : 允许的 origin 模式列表（逗号分隔）
 * <p>
 * 生产环境务必显式配置为具体域名，例如：
 * kbook.cors.allowed-origin-patterns=https://book.keiskei.top
 */
@Configuration
public class CorsConfig {

    /**
     * 默认的 origin 模式列表 — 覆盖常见开发场景（任意 host/port）。
     * 注意：使用 setAllowedOriginPatterns() 而非 setAllowedOrigins()，前者允许通配符与 credentials 并存。
     */
    private static final String DEFAULT_PATTERNS =
            "http://*:*," +
            "https://*:*," +
            "http://*," +
            "https://*";

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
        config.setAllowedOriginPatterns(patterns);

        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        config.setExposedHeaders(List.of("Authorization", "Content-Disposition"));
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);

        return new CorsFilter(source);
    }
}
