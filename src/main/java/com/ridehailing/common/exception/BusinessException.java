package com.ridehailing.common.exception;

import com.ridehailing.common.error.ErrorCode;

/** Expected, domain level failure. Always maps to a deterministic HTTP status. */
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }
}
