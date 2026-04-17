package com.kbook.config;

import lombok.RequiredArgsConstructor;
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
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final CorsFilter corsFilter;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                // 禁用 CSRF（JWT 无状态不需要）
                .csrf(AbstractHttpConfigurer::disable)
                // 启用 CORS（使用 CorsConfig 中定义的 CorsFilter bean）
                .cors(Customizer.withDefaults())
                // 禁用 Session
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // 未认证时返回 401 而非 403（方便前端 refresh token）
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, authException) -> {
                            response.setStatus(401);
                            response.setContentType("application/json;charset=UTF-8");
                            response.getWriter().write("{\"code\":401,\"message\":\"未登录或登录已过期\",\"data\":null}");
                        }))
                // 接口权限配置
                .authorizeHttpRequests(auth -> auth
                        // 公开接口（登录/注册/验证码/刷新Token/重置密码/图形验证码）
                        .requestMatchers("/api/auth/send-code").permitAll()
                        .requestMatchers("/api/auth/login/**").permitAll()
                        .requestMatchers("/api/auth/register").permitAll()
                        .requestMatchers("/api/auth/refresh").permitAll()
                        .requestMatchers("/api/auth/reset-password").permitAll()
                        .requestMatchers("/api/captcha/**").permitAll()
                        // 需认证的认证接口（修改密码/登出）
                        .requestMatchers("/api/auth/change-password").authenticated()
                        .requestMatchers("/api/auth/logout").authenticated()
                        // 其他公开接口
                        .requestMatchers("/api/public/**").permitAll()
                        .requestMatchers("/api/health").permitAll()
                        // 上传文件访问（头像等）
                        .requestMatchers("/api/uploads/**").permitAll()
                        // 管理员接口
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .requestMatchers("/api/books/admin/**").hasRole("ADMIN")
                        // 图书管理接口需管理员（具体操作优先于通用规则）
                        .requestMatchers(HttpMethod.POST, "/api/books/reindex").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/api/books/*/tags").hasRole("ADMIN")
                        // 图书入库和修改需认证
                        .requestMatchers(HttpMethod.POST, "/api/books").authenticated()
                        .requestMatchers(HttpMethod.PUT, "/api/books/**").authenticated()
                        .requestMatchers(HttpMethod.DELETE, "/api/books/**").authenticated()
                        // 图书文件读取需认证（版权保护）
                        .requestMatchers(HttpMethod.GET, "/api/books/*/file").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/books/*/text-info").authenticated()
                        // 图书浏览公开（搜索/排行/详情/封面）- 放在后面避免前面的规则先匹配
                        .requestMatchers(HttpMethod.GET, "/api/books/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/book-files/**").permitAll()
                        // 评论浏览公开（GET），其他操作需认证
                        .requestMatchers(HttpMethod.GET, "/api/comments/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/comments").authenticated()
                        .requestMatchers(HttpMethod.DELETE, "/api/comments/**").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/comments/*/like").authenticated()
                        .requestMatchers(HttpMethod.DELETE, "/api/comments/*/like").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/comments/*/favorite").authenticated()
                        .requestMatchers(HttpMethod.DELETE, "/api/comments/*/favorite").authenticated()
                        // 用户主页公开浏览
                        .requestMatchers(HttpMethod.GET, "/api/user-profile/**").permitAll()
                        // 关注列表公开浏览，但关注/取消关注需认证
                        .requestMatchers(HttpMethod.GET, "/api/follow/*/followings").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/follow/*/followers").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/follow/is-following/**").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/follow/**").authenticated()
                        .requestMatchers(HttpMethod.DELETE, "/api/follow/**").authenticated()
                        // 书架相关全部需认证
                        .requestMatchers("/api/bookshelf/**").authenticated()
                        // 阅读进度需认证
                        .requestMatchers("/api/progress/**").authenticated()
                        // AI 相关需认证
                        .requestMatchers("/api/ai/**").authenticated()
                        // 推荐需认证
                        .requestMatchers("/api/recommend/**").authenticated()
                        // 通知需认证
                        .requestMatchers("/api/notifications/**").authenticated()
                        // 用户信息（除公开profile外）需认证
                        .requestMatchers("/api/user/**").authenticated()
                        // 首页数据需认证（包含个性化推荐）
                        .requestMatchers("/api/home/**").authenticated()
                        // 其他接口需认证
                        .anyRequest().authenticated()
                )
                // JWT 过滤器
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
