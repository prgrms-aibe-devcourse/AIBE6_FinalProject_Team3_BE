package com.algogyeyak.global.error;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
    INVALID_INPUT(HttpStatus.BAD_REQUEST, "COMMON_400", "잘못된 요청입니다."),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "COMMON_401", "인증이 필요합니다."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "COMMON_403", "접근 권한이 없습니다."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "COMMON_404", "요청한 리소스를 찾을 수 없습니다."),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "COMMON_405", "지원하지 않는 HTTP 메서드입니다."),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "COMMON_415", "지원하지 않는 미디어 타입입니다."),
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "COMMON_500", "서버 내부 오류가 발생했습니다."),

    // Property 도메인
    PROPERTY_NOT_FOUND(HttpStatus.NOT_FOUND, "PROPERTY_NOT_FOUND", "존재하지 않는 매물입니다."),
    PROPERTY_ACCESS_DENIED(HttpStatus.FORBIDDEN, "PROPERTY_ACCESS_DENIED", "본인이 등록한 매물만 접근할 수 있습니다."),
    PROPERTY_REQUIRED_FIELD_MISSING(HttpStatus.BAD_REQUEST, "PROPERTY_REQUIRED_FIELD_MISSING", "필수 입력값이 누락되었습니다."),
    PROPERTY_INVALID_PRICE(HttpStatus.BAD_REQUEST, "PROPERTY_INVALID_PRICE", "거래 유형에 맞지 않는 가격 정보입니다."),
    PROPERTY_TYPE_NOT_SUPPORTED(HttpStatus.BAD_REQUEST, "PROPERTY_TYPE_NOT_SUPPORTED", "지원하지 않는 매물 유형입니다."),
    PROPERTY_ADDRESS_RESOLUTION_FAILED(HttpStatus.UNPROCESSABLE_ENTITY, "PROPERTY_ADDRESS_RESOLUTION_FAILED", "입력한 주소를 확인할 수 없습니다."),
    PROPERTY_DUPLICATE(HttpStatus.CONFLICT, "PROPERTY_DUPLICATE", "이미 동일한 조건으로 등록된 매물이 있습니다."),
    PROPERTY_IMAGE_INVALID(HttpStatus.BAD_REQUEST, "PROPERTY_IMAGE_INVALID", "이미지 형식 또는 크기가 올바르지 않습니다."),
    PROPERTY_ALREADY_DELETED(HttpStatus.CONFLICT, "PROPERTY_ALREADY_DELETED", "이미 삭제된 매물입니다."),
    PROPERTY_INVALID_SEARCH_CONDITION(HttpStatus.BAD_REQUEST, "PROPERTY_INVALID_SEARCH_CONDITION", "검색 조건이 올바르지 않습니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;

    ErrorCode(HttpStatus status, String code, String message) {
        this.status = status;
        this.code = code;
        this.message = message;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }
}
