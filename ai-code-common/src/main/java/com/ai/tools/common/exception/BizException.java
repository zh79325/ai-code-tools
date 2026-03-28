package com.ai.tools.common.exception;

/**
 * 业务异常
 */
public class BizException extends RuntimeException {

    private final String code;

    public BizException(String message) {
        super(message);
        this.code = "BIZ_ERROR";
    }

    public BizException(String code, String message) {
        super(message);
        this.code = code;
    }

    public BizException(String message, Throwable cause) {
        super(message, cause);
        this.code = "BIZ_ERROR";
    }

    public String getCode() {
        return code;
    }
}
