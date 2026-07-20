package com.fast.backend.common.api;

import com.fast.backend.common.exception.ErrorCode;

public class ErrorResponse {

    private final String code;
    private final String message;

    public ErrorResponse(String code, String message) {
        this.code = code;
        this.message = message;
    }

    public static ErrorResponse of(ErrorCode errorCode) {
        return new ErrorResponse(errorCode.name(), errorCode.getDefaultMessage());
    }

    public static ErrorResponse of(ErrorCode errorCode, String message) {
        return new ErrorResponse(errorCode.name(), message);
    }

    public String getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }
}
