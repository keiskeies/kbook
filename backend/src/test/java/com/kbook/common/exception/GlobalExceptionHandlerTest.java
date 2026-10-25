package com.kbook.common.exception;

import com.kbook.common.api.ErrorCode;
import com.kbook.common.api.Result;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 设计文档附录 I：入参笔误曾掉进兜底分支，被报成 500「系统繁忙，请稍后重试」。
 *
 * 锁住三件事：消息点名是哪个参数；不复读提交值（错误消息会被前端渲染，历史入参里出现过
 * {@code {{7*7}}} 这类注入串）；两类标量入参异常都注册在 {@code handleParam} 上 —— 漏注册
 * 只在运行时才暴露，所以用注解反射断言。
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    private static MethodArgumentTypeMismatchException typeMismatch(String name, Object value, Class<?> requiredType) {
        try {
            Method method = Target.class.getMethod("take", requiredType);
            return new MethodArgumentTypeMismatchException(
                    value, requiredType, name, new MethodParameter(method, 0), null);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 只为拼出 MethodArgumentTypeMismatchException 所需的 MethodParameter。 */
    @SuppressWarnings("unused")
    private static final class Target {
        public void take(Long bookId) {
        }

        public void take(Integer days) {
        }
    }

    @Test
    @DisplayName("类型不匹配：报参数名，不报提交值")
    void typeMismatchNamesParameterAndNotValue() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        Result<?> result = handler.handleParam(typeMismatch("bookId", "{{7*7}}", Long.class), response);

        assertThat(result.getCode()).isEqualTo(ErrorCode.PARAM_INVALID.code());
        assertThat(result.getMessage()).isEqualTo("参数「bookId」取值不合法");
        assertThat(result.getMessage()).doesNotContain("{{7*7}}");
        assertThat(response.getContentType()).startsWith("application/json");
    }

    @Test
    @DisplayName("缺必填参数：报参数名")
    void missingParamNamesParameter() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        Result<?> result = handler.handleParam(
                new MissingServletRequestParameterException("days", "Integer", true), response);

        assertThat(result.getCode()).isEqualTo(ErrorCode.PARAM_INVALID.code());
        assertThat(result.getMessage()).isEqualTo("缺少必填参数「days」");
    }

    @Test
    @DisplayName("不再复用兜底的系统繁忙消息")
    void paramErrorIsNotReportedAsSystemFault() {
        Result<?> result = handler.handleParam(typeMismatch("days", "abc", Integer.class), new MockHttpServletResponse());

        assertThat(result.getMessage()).isNotEqualTo(ErrorCode.SYSTEM_ERROR.defaultMessage());
        assertThat(result.getCode()).isNotEqualTo(ErrorCode.SYSTEM_ERROR.code());
    }

    @Test
    @DisplayName("两类标量入参异常都注册在 handleParam 且都是 400")
    void scalarParamExceptionsAreAllRegistered() throws Exception {
        Method handleParam = GlobalExceptionHandler.class.getMethod("handleParam", Exception.class, HttpServletResponse.class);

        assertThat(handleParam.getAnnotation(ExceptionHandler.class).value())
                .containsExactlyInAnyOrder(MethodArgumentTypeMismatchException.class,
                        MissingServletRequestParameterException.class);
        assertThat(handleParam.getAnnotation(ResponseStatus.class).value()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
