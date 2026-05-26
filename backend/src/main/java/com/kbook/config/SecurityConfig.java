package com.kbook.config;

import jakarta.servlet.DispatcherType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.CorsFilter;

/**
 * Spring Security 配置 - JWT 无状态认证 + CORS + 接口权限
 */
@Slf4j
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    /** JWT 认证过滤器 */
    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    /** 跨域过滤器 */
    private final CorsFilter corsFilter;

    /**
     * 配置安全过滤链 — JWT 无状态认证 + 接口权限控制
     *
     * @param http HttpSecurity 构建器
     * @return SecurityFilterChain 实例
     */
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                // 只对 REQUEST 分发进行安全过滤，跳过 ASYNC/ERROR/FORWARD/INCLUDE 分发
                // 避免 SSE 异步响应完成后 SecurityContext 已清理导致 AuthorizationDeniedException
                .securityMatcher(request ->
                        request.getDispatcherType() == DispatcherType.REQUEST)
                // 禁用 CSRF（JWT 无状态不需要）
                .csrf(AbstractHttpConfigurer::disable)
                // 启用 CORS（使用 CorsConfig 中定义的 CorsFilter bean）
                .cors(Customizer.withDefaults())
                // 禁用 Session
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // 未认证时返回 401 而非 403（方便前端 refresh token）
                // 权限不足时返回 403 JSON（避免 AuthorizationDeniedException 被全局异常处理器记录）
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, authException) -> {
                            response.setStatus(401);
                            response.setContentType("application/json;charset=UTF-8");
                            response.getWriter().write("{\"code\":401,\"message\":\"未登录或登录已过期\",\"data\":null}");
                        })
                        .accessDeniedHandler((request, response, accessDeniedException) -> {
                            // 注意：此处理在 AuthorizationFilter 中执行，
                            // 如果已提交响应则不再处理（避免 SSE 等异步场景异常）
                            if (response.isCommitted()) {
                                log.debug("授权被拒绝（响应已提交，忽略）: {}", accessDeniedException.getMessage());
                                return;
                            }
                            response.setStatus(403);
                            response.setContentType("application/json;charset=UTF-8");
                            response.getWriter().write("{\"code\":403,\"message\":\"权限不足，无法访问此资源\",\"data\":null}");
                        }))
                // 接口权限配置（注意：规则按从上到下顺序匹配，第一个匹配的规则生效）
                .authorizeHttpRequests(auth -> auth
                        // ===== 公开接口（无需认证）=====
                        .requestMatchers("/api/health").permitAll()
                        .requestMatchers("/api/auth/send-code").permitAll()
                        .requestMatchers("/api/auth/login/**").permitAll()
                        .requestMatchers("/api/auth/register").permitAll()
                        .requestMatchers("/api/auth/refresh").permitAll()
                        .requestMatchers("/api/auth/reset-password").permitAll()
                        .requestMatchers("/api/captcha/**").permitAll()
                        .requestMatchers("/api/public/**").permitAll()
                        .requestMatchers("/api/user/avatar/**").permitAll()
                        .requestMatchers("/ws/**").permitAll()
                        .requestMatchers("/api/ws/**").permitAll()
                        
                        // ===== 管理员接口（需 ADMIN 角色）=====
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .requestMatchers("/api/books/admin/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/books/reindex").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/api/books/*/tags").hasRole("ADMIN")
                        
                        // ===== 图书相关接口 =====
                        .requestMatchers(HttpMethod.GET, "/api/books/*/file").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/books/*/text-info").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/books/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/book-files/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/books").authenticated()
                        .requestMatchers(HttpMethod.PUT, "/api/books/**").authenticated()
                        .requestMatchers(HttpMethod.DELETE, "/api/books/**").authenticated()
                        
                        // ===== 评论相关接口 =====
                        .requestMatchers(HttpMethod.GET, "/api/comments/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/comments").authenticated()
                        .requestMatchers(HttpMethod.DELETE, "/api/comments/**").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/comments/*/like").authenticated()
                        .requestMatchers(HttpMethod.DELETE, "/api/comments/*/like").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/comments/*/favorite").authenticated()
                        .requestMatchers(HttpMethod.DELETE, "/api/comments/*/favorite").authenticated()
                        
                        // ===== 用户相关接口（公开浏览 + 认证操作）=====
                        .requestMatchers(HttpMethod.GET, "/api/user/*/profile").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/user/*/books").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/user/*/comments").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/user/*/followings").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/user/*/followers").permitAll()
                        .requestMatchers("/api/user/**").authenticated()
                        
                        // ===== 需认证的接口 =====
                        .requestMatchers("/api/auth/change-password").authenticated()
                        .requestMatchers("/api/auth/logout").authenticated()
                        .requestMatchers("/api/bookshelf/**").authenticated()
                        .requestMatchers("/api/progress/**").authenticated()
                        .requestMatchers("/api/ai/**").authenticated()
                        .requestMatchers("/api/recommend/**").authenticated()
                        .requestMatchers("/api/chat/**").authenticated()
                        .requestMatchers("/api/home/**").authenticated()
                        
                        .anyRequest().authenticated()
                )
                // JWT 过滤器
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * 密码编码器 — 使用 BCrypt 算法
     *
     * @return PasswordEncoder 实例
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
