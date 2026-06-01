package com.kbook.common.exception;

import com.kbook.common.api.ErrorCode;
import lombok.Getter;

/**
 * 业务异常 — 携带语义化错误码。
 * <p>
 * 优先使用 {@link #BusinessException(ErrorCode, String)} 以保证错误码一致性。
 */
@Getter
public class BusinessException extends RuntimeException {

    private final int code;

    public BusinessException(String message) {
        super(message);
        this.code = ErrorCode.GENERIC_ERROR.code();
    }

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.defaultMessage());
        this.code = errorCode.code();
    }

    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.code = errorCode.code();
    }

    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }
}
