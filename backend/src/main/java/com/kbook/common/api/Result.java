package com.kbook.common.api;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 统一响应结果
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Result<T> {

    /** 状态码，0表示成功，非0表示失败 */
    private int code;
    /** 响应消息 */
    private String message;
    /** 响应数据 */
    private T data;

    /**
     * 成功响应（带数据）
     *
     * @param data 响应数据
     * @return 成功结果
     */
    public static <T> Result<T> ok(T data) {
        return new Result<>(0, "success", data);
    }

    /**
     * 成功响应（无数据）
     *
     * @return 成功结果
     */
    public static <T> Result<T> ok() {
        return new Result<>(0, "success", null);
    }

    /**
     * 失败响应（默认错误码 1）
     *
     * @param message 错误消息
     * @return 失败结果
     */
    public static <T> Result<T> fail(String message) {
        return new Result<>(ErrorCode.GENERIC_ERROR.code(), message, null);
    }

    /**
     * 失败响应（使用预定义 ErrorCode）
     */
    public static <T> Result<T> fail(ErrorCode errorCode) {
        return new Result<>(errorCode.code(), errorCode.defaultMessage(), null);
    }

    /**
     * 失败响应（使用预定义 ErrorCode + 自定义消息）
     */
    public static <T> Result<T> fail(ErrorCode errorCode, String message) {
        return new Result<>(errorCode.code(), message, null);
    }

    /**
     * 失败响应（自定义错误码）
     *
     * @param code    错误码
     * @param message 错误消息
     * @return 失败结果
     */
    public static <T> Result<T> fail(int code, String message) {
        return new Result<>(code, message, null);
    }
}
