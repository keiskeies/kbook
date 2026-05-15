package com.kbook.common.exception;

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

    @ExceptionHandler(BusinessException.class)
    @ResponseStatus(HttpStatus.OK)
    public Result<?> handleBusinessException(BusinessException e, HttpServletResponse response) {
        // 重置 Content-Type 为 JSON，避免文件下载等接口异常时 Content-Type 不匹配
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        log.warn("业务异常: {}", e.getMessage());
        return Result.fail(e.getCode(), e.getMessage());
    }

    @ExceptionHandler(BadCredentialsException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public Result<?> handleBadCredentials(BadCredentialsException e, HttpServletResponse response) {
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        return Result.fail(401, "用户名或密码错误");
    }

    @ExceptionHandler(AccessDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public Result<?> handleAccessDenied(AccessDeniedException e, HttpServletResponse response) {
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        log.debug("访问被拒绝: {}", e.getMessage());
        return Result.fail(403, "您没有权限访问此资源");
    }

    /** Spring Security 6.x 方法级权限拒绝（@PreAuthorize 等） */
    @ExceptionHandler(AuthorizationDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public Result<?> handleAuthorizationDenied(AuthorizationDeniedException e, HttpServletResponse response) {
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        log.debug("授权被拒绝: {}", e.getMessage());
        return Result.fail(403, "您没有权限执行此操作");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<?> handleValidation(MethodArgumentNotValidException e, HttpServletResponse response) {
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .findFirst()
                .orElse("参数校验失败");
        return Result.fail(400, message);
    }

    @ExceptionHandler(BindException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<?> handleBindException(BindException e, HttpServletResponse response) {
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .findFirst()
                .orElse("参数绑定失败");
        return Result.fail(400, message);
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<?> handleException(Exception e, HttpServletResponse response) {
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        log.error("系统异常: ", e);
        return Result.fail(500, "系统繁忙，请稍后重试");
    }
}
