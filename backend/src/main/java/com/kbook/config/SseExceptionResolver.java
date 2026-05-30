package com.kbook.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.core.Ordered;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;

/**
 * SSE 异常解析器 — 处理 SSE 流式响应完成后的授权异常
 * <p>
 * 问题：Spring Security 6.x 在 SSE 响应已提交后仍会执行权限检查，
 * 导致 AuthorizationDeniedException 被创建和记录（即使响应已成功发送）。
 * <p>
 * 解决：在响应已提交时完全跳过异常处理，避免异常被 Spring MVC 记录。
 */
@Slf4j
@Component
public class SseExceptionResolver implements HandlerExceptionResolver, Ordered {

    /**
     * 最高优先级，确保在其他异常处理器之前执行
     */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    /**
     * 解析异常 — 仅处理 SSE 响应已提交后的授权异常
     * <p>
     * 响应已提交时返回空 ModelAndView（异常已处理），否则返回 null（继续传播）。
     *
     * @param request  HTTP 请求
     * @param response HTTP 响应
     * @param handler  处理器
     * @param ex       异常
     * @return ModelAndView-已处理, null-未处理
     */
    @Override
    public ModelAndView resolveException(@NonNull HttpServletRequest request,
                                         @NonNull HttpServletResponse response,
                                         Object handler,
                                         @NonNull Exception ex) {
        // 仅处理 AuthorizationDeniedException
        if (ex instanceof AuthorizationDeniedException && response.isCommitted()) {
            log.debug("SSE 响应已提交，跳过授权异常处理: uri={}", request.getRequestURI());
            // 返回空的 ModelAndView 表示异常已处理，不再传播
            return new ModelAndView();
        }
        // 返回 null 表示异常未处理，继续传播
        return null;
    }
}
