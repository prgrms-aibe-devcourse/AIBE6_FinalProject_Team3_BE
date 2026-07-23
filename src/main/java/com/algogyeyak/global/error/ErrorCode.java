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
    INVALID_SORT_FIELD(HttpStatus.BAD_REQUEST, "COMMON_400_SORT", "허용되지 않는 정렬 기준입니다."),

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
    PROPERTY_INVALID_SEARCH_CONDITION(HttpStatus.BAD_REQUEST, "PROPERTY_INVALID_SEARCH_CONDITION", "검색 조건이 올바르지 않습니다."),

    // Contract-Analysis 도메인
    CONTRACT_ANALYSIS_INVALID_INPUT(HttpStatus.BAD_REQUEST, "CONTRACT_ANALYSIS_INVALID_INPUT", "입력(이미지 또는 텍스트)이 없습니다."),
    CONTRACT_ANALYSIS_UNSUPPORTED_FILE_TYPE(HttpStatus.BAD_REQUEST, "CONTRACT_ANALYSIS_UNSUPPORTED_FILE_TYPE", "지원하지 않는 이미지 형식입니다."),
    CONTRACT_ANALYSIS_FILE_TOO_LARGE(HttpStatus.BAD_REQUEST, "CONTRACT_ANALYSIS_FILE_TOO_LARGE", "이미지 크기가 허용 범위를 초과했습니다."),
    CONTRACT_ANALYSIS_TEXT_TOO_SHORT(HttpStatus.BAD_REQUEST, "CONTRACT_ANALYSIS_TEXT_TOO_SHORT", "입력한 텍스트가 너무 짧습니다."),
    CONTRACT_ANALYSIS_NOT_RELATED(HttpStatus.BAD_REQUEST, "CONTRACT_ANALYSIS_NOT_RELATED", "부동산 계약과 관련이 없는 입력입니다."),
    CONTRACT_ANALYSIS_FORBIDDEN(HttpStatus.FORBIDDEN, "CONTRACT_ANALYSIS_FORBIDDEN", "본인이 등록한 매물만 계약 분석에 사용할 수 있습니다."),
    CONTRACT_ANALYSIS_OCR_LOW_CONFIDENCE(HttpStatus.UNPROCESSABLE_CONTENT, "CONTRACT_ANALYSIS_OCR_LOW_CONFIDENCE", "OCR 인식 신뢰도가 낮습니다. 직접 입력을 이용해주세요."),
    CONTRACT_ANALYSIS_OCR_EMPTY_RESULT(HttpStatus.UNPROCESSABLE_CONTENT, "CONTRACT_ANALYSIS_OCR_EMPTY_RESULT", "OCR 인식 결과가 없습니다."),
    CONTRACT_ANALYSIS_OCR_API_ERROR(HttpStatus.BAD_GATEWAY, "CONTRACT_ANALYSIS_OCR_API_ERROR", "OCR 서비스 연동 중 오류가 발생했습니다."),
    CONTRACT_ANALYSIS_MASKING_NOT_CONFIRMED(HttpStatus.BAD_REQUEST, "CONTRACT_ANALYSIS_MASKING_NOT_CONFIRMED", "마스킹 확인이 완료되지 않았습니다."),
    CONTRACT_ANALYSIS_MASKING_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "CONTRACT_ANALYSIS_MASKING_FAILED", "마스킹 처리 중 오류가 발생했습니다."),
    CONTRACT_ANALYSIS_AI_RESPONSE_INVALID(HttpStatus.BAD_GATEWAY, "CONTRACT_ANALYSIS_AI_RESPONSE_INVALID", "AI 응답 형식이 올바르지 않습니다."),
    CONTRACT_ANALYSIS_AI_HALLUCINATION(HttpStatus.BAD_GATEWAY, "CONTRACT_ANALYSIS_AI_HALLUCINATION", "AI가 입력에 없는 내용을 생성했습니다."),
    CONTRACT_ANALYSIS_AI_API_ERROR(HttpStatus.BAD_GATEWAY, "CONTRACT_ANALYSIS_AI_API_ERROR", "AI 분석 서비스 연동 중 오류가 발생했습니다.");

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
