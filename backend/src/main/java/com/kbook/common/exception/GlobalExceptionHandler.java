package com.kbook.common.exception;

import com.kbook.common.api.ErrorCode;
import com.kbook.common.api.Result;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理器
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 处理业务异常，返回业务错误码和消息
     */
    @ExceptionHandler(BusinessException.class)
    @ResponseStatus(HttpStatus.OK)
    public Result<?> handleBusinessException(BusinessException e, HttpServletResponse response) {
        // 重置 Content-Type 为 JSON，避免文件下载等接口异常时 Content-Type 不匹配
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        log.warn("业务异常: {}", e.getMessage());
        return Result.fail(e.getCode(), e.getMessage());
    }

    /**
     * 处理登录凭证错误（用户名或密码错误）
     */
    @ExceptionHandler(BadCredentialsException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public Result<?> handleBadCredentials(BadCredentialsException e, HttpServletResponse response) {
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        return Result.fail(ErrorCode.UNAUTHORIZED, "用户名或密码错误");
    }

    /**
     * 处理访问被拒绝异常（URL级别权限控制）
     */
    @ExceptionHandler(AccessDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public Result<?> handleAccessDenied(AccessDeniedException e, HttpServletResponse response) {
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        log.debug("访问被拒绝: {}", e.getMessage());
        return Result.fail(ErrorCode.FORBIDDEN, "您没有权限访问此资源");
    }

    /**
     * 处理 Spring Security 6.x 方法级权限拒绝（@PreAuthorize 等）
     * <p>当 SSE 流式响应已提交时，后续权限检查触发的异常会被忽略</p>
     */
    @ExceptionHandler(AuthorizationDeniedException.class)
    public Result<?> handleAuthorizationDenied(AuthorizationDeniedException e, HttpServletResponse response) {
        if (response.isCommitted()) {
            log.debug("授权被拒绝（响应已提交，忽略）: {}", e.getMessage());
            return null;
        }
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        log.debug("授权被拒绝: {}", e.getMessage());
        return Result.fail(ErrorCode.FORBIDDEN, "您没有权限执行此操作");
    }

    /**
     * 处理请求参数校验失败异常（@Valid 注解触发）
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<?> handleValidation(MethodArgumentNotValidException e, HttpServletResponse response) {
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .findFirst()
                .orElse("参数校验失败");
        return Result.fail(ErrorCode.PARAM_INVALID, message);
    }

    /**
     * 处理参数绑定异常（请求参数类型不匹配等）
     */
    @ExceptionHandler(BindException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<?> handleBindException(BindException e, HttpServletResponse response) {
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .findFirst()
                .orElse("参数绑定失败");
        return Result.fail(ErrorCode.PARAM_INVALID, message);
    }

    /**
     * 兜底异常处理 — 记录完整堆栈，返回脱敏后的通用错误消息
     */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<?> handleException(Exception e, HttpServletResponse response) {
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        log.error("系统异常: ", e);
        return Result.fail(ErrorCode.SYSTEM_ERROR);
    }
}
