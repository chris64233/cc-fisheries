package com.chris64233.cc.fisheries.common;

import org.springframework.http.HttpStatus;

/**
 * 业务异常，携带对应的 HTTP 状态码；抛出时当前事务回滚，不写入台账。
 */
public class BusinessException extends RuntimeException {

    private final HttpStatus status;

    public BusinessException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public static BusinessException notFound(String message) {
        return new BusinessException(HttpStatus.NOT_FOUND, message);
    }

    public static BusinessException conflict(String message) {
        return new BusinessException(HttpStatus.CONFLICT, message);
    }

    public static BusinessException unprocessable(String message) {
        return new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, message);
    }
}
