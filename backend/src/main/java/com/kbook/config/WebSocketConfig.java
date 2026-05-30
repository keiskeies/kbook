package com.kbook.config;

import com.kbook.config.JwtUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

import java.util.List;
import java.util.Map;

/**
 * WebSocket 配置 — 基于 STOMP 协议的消息代理
 * <p>
 * 端点：/api/ws/chat（SockJS 降级支持）
 * 消息代理：/queue（点对点）、/topic（广播）
 * 应用目标前缀：/app，用户目标前缀：/user
 * <p>
 * 连接认证：通过 STOMP CONNECT 帧的 Authorization 头传递 JWT Token
 */
@Slf4j
@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    /** JWT 工具类（用于 WebSocket 连接认证） */
    private final JwtUtil jwtUtil;

    /**
     * 注册 STOMP 端点 — 前端通过此端点建立 WebSocket 连接
     */
    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/api/ws/chat")
                .setAllowedOriginPatterns("*")
                .withSockJS();
    }

    /**
     * 配置消息代理 — 定义消息路由前缀
     */
    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/queue", "/topic");
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
    }

    /**
     * 配置客户端入站通道拦截器 — 在 STOMP CONNECT 帧中提取 JWT Token 进行认证
     */
    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new ChannelInterceptor() {
            @Override
            public Message<?> preSend(Message<?> message, MessageChannel channel) {
                StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
                
                if (accessor != null && StompCommand.CONNECT.equals(accessor.getCommand())) {
                    // 从 STOMP CONNECT 帧的 Authorization 头提取 Token
                    List<String> authorization = accessor.getNativeHeader("Authorization");
                    if (authorization != null && !authorization.isEmpty()) {
                        String token = authorization.get(0).replace("Bearer ", "");
                        try {
                            // 解析 Token 获取用户信息
                            Map<String, Object> claims = jwtUtil.parseToken(token);
                            long userId = ((Number) claims.get("userId")).longValue();
                            
                            // 设置 WebSocket 会话的用户主体
                            accessor.setUser(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                                    Long.toString(userId),
                                    null,
                                    List.of(new SimpleGrantedAuthority("ROLE_USER"))
                            ));
                        } catch (Exception e) {
                            log.warn("WebSocket authentication failed: {}", e.getMessage());
                        }
                    }
                }
                return message;
            }
        });
    }
}