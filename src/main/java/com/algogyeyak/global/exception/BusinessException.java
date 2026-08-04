package com.algogyeyak.global.exception;

import com.algogyeyak.global.error.ErrorCode;
import org.springframework.http.HttpStatus;

public class BusinessException extends RuntimeException {
    private final ErrorCode errorCode;
    private final HttpStatus status;
    private final String fallback;

    public BusinessException(ErrorCode errorCode) {
        this(errorCode, errorCode.getMessage(), errorCode.getStatus(), null);
    }

    public BusinessException(ErrorCode errorCode, String message) {
        this(errorCode, message, errorCode.getStatus(), null);
    }

    // 외부 API 장애 유형에 따라 같은 ErrorCode라도 응답 상태 코드를 다르게 내려야 하는 경우
    // (e.g. Clova OCR 타임아웃은 504, 그 외 실패는 ErrorCode 기본값인 502) 사용.
    public BusinessException(ErrorCode errorCode, String message, HttpStatus status) {
        this(errorCode, message, status, null);
    }

    // 클라이언트가 대체 플로우로 전환해야 함을 알리는 힌트(e.g. "MANUAL_INPUT")를 함께 내려야 하는 경우 사용.
    public BusinessException(ErrorCode errorCode, String message, String fallback) {
        this(errorCode, message, errorCode.getStatus(), fallback);
    }

    private BusinessException(ErrorCode errorCode, String message, HttpStatus status, String fallback) {
        super(message);
        this.errorCode = errorCode;
        this.status = status;
        this.fallback = fallback;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getFallback() {
        return fallback;
    }
}
