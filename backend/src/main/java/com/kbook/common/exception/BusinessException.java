package com.kbook.common.exception;

import lombok.Getter;

/**
 * 业务异常
 */
@Getter
public class BusinessException extends RuntimeException {

    /** 业务错误码 */
    private final int code;

    /**
     * 使用默认错误码1构造业务异常
     *
     * @param message 错误消息
     */
    public BusinessException(String message) {
        super(message);
        this.code = 1;
    }

    /**
     * 使用自定义错误码构造业务异常
     *
     * @param code    业务错误码
     * @param message 错误消息
     */
    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }
}
