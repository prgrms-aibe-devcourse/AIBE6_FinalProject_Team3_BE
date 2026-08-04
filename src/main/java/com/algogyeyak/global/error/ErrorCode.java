package com.algogyeyak.global.error;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
    BAD_REQUEST(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "잘못된 요청입니다."),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "인증이 필요합니다."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "FORBIDDEN", "접근 권한이 없습니다."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "NOT_FOUND", "요청한 리소스를 찾을 수 없습니다."),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "지원하지 않는 HTTP 메서드입니다."),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE", "지원하지 않는 미디어 타입입니다."),
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_SERVER_ERROR", "서버 내부 오류가 발생했습니다."),
    INVALID_SORT_FIELD(HttpStatus.BAD_REQUEST, "INVALID_SORT_FIELD", "허용되지 않는 정렬 기준입니다."),

    // Auth 도메인
    AUTH_EMAIL_ALREADY_EXISTS(HttpStatus.CONFLICT, "AUTH_EMAIL_ALREADY_EXISTS", "이미 가입된 이메일입니다."),
    AUTH_NICKNAME_ALREADY_EXISTS(HttpStatus.CONFLICT, "AUTH_NICKNAME_ALREADY_EXISTS", "이미 사용 중인 닉네임입니다."),
    AUTH_INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "AUTH_INVALID_CREDENTIALS", "이메일 또는 비밀번호가 올바르지 않습니다."),
    AUTH_TOKEN_MISSING(HttpStatus.UNAUTHORIZED, "AUTH_TOKEN_MISSING", "인증 토큰이 없습니다."),
    AUTH_TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "AUTH_TOKEN_INVALID", "유효하지 않은 토큰입니다."),
    AUTH_TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "AUTH_TOKEN_EXPIRED", "토큰이 만료되었습니다."),
    // 이미 access token으로 인증된 사용자가 계정 상태/추가 검증 때문에 이 작업을 할 수 없는 경우이므로
    // (인증 자체가 안 된 게 아님) 401이 아니라 403으로 통일한다 — setPassword()의 세 실패 케이스
    // (이메일 없음/현재 비밀번호 불일치/dev-login 계정)가 전부 같은 성격이면서도 이전엔 400/401/403이
    // 뒤섞여 있었다.
    AUTH_EMAIL_REQUIRED_FOR_PASSWORD(HttpStatus.FORBIDDEN, "AUTH_EMAIL_REQUIRED_FOR_PASSWORD", "이메일이 연동되지 않은 계정은 비밀번호를 설정할 수 없습니다."),
    // login()의 AUTH_INVALID_CREDENTIALS(401, 미인증 컨텍스트)와 의미가 달라 별도 코드로 분리했다 —
    // 여긴 이미 인증된 사용자가 2차 확인(현재 비밀번호)에 실패한 경우라 403이 맞다.
    AUTH_CURRENT_PASSWORD_MISMATCH(HttpStatus.FORBIDDEN, "AUTH_CURRENT_PASSWORD_MISMATCH", "현재 비밀번호가 올바르지 않습니다."),
    // access token(AUTH_TOKEN_MISSING/INVALID/EXPIRED)과 동일한 세분화 패턴을 refresh token에도
    // 맞춘다 - 이전엔 쿠키 없음/토큰 못 찾음/만료/탈퇴 사용자가 전부 UNAUTHORIZED 하나로 뭉뚱그려져
    // message 텍스트로만 구분 가능했다.
    AUTH_REFRESH_TOKEN_MISSING(HttpStatus.UNAUTHORIZED, "AUTH_REFRESH_TOKEN_MISSING", "Refresh Token이 없습니다."),
    // 탈퇴한 사용자의 refresh token도 이 코드로 처리한다 - 클라이언트 입장에서 "재로그인이 필요하다"는
    // 결론은 동일하고, 계정 존재 여부를 굳이 구분해 알려줄 필요가 없다.
    // Redis TTL이 만료 판단의 유일한 소스가 된 이후로는(RefreshTokenService 참고), 자연 만료도
    // "찾을 수 없음"으로 Redis에서 evict되어 이 코드와 구분이 불가능해졌다 - 그래서 EXPIRED 전용
    // 코드는 만들지 않고 둘 다 여기로 처리한다.
    AUTH_REFRESH_TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "AUTH_REFRESH_TOKEN_INVALID", "유효하지 않은 Refresh Token입니다."),
    // Redis(access token blacklist/refresh token 저장소) 장애 시 fail-closed 정책 — "이 토큰이
    // 유효한지 확신할 수 없다"를 "유효하다"로 오인하지 않기 위해, 인증을 통과시키는 대신 503으로
    // 명시적으로 실패시킨다. 재시도하면 복구될 수 있는 일시 장애라는 걸 알리기 위해 401이 아닌 503을 쓴다.
    AUTH_TOKEN_STORE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "AUTH_TOKEN_STORE_UNAVAILABLE", "인증 저장소에 일시적으로 연결할 수 없습니다. 잠시 후 다시 시도해주세요."),

    // User 도메인
    USER_PROFILE_ALREADY_EXISTS(HttpStatus.CONFLICT, "USER_PROFILE_ALREADY_EXISTS", "이미 프로필이 등록되어 있습니다."),
    USER_NICKNAME_ALREADY_EXISTS(HttpStatus.CONFLICT, "USER_NICKNAME_ALREADY_EXISTS", "이미 사용 중인 닉네임입니다."),

    // Property 도메인
    PROPERTY_NOT_FOUND(HttpStatus.NOT_FOUND, "PROPERTY_NOT_FOUND", "존재하지 않는 매물입니다."),
    PROPERTY_ACCESS_DENIED(HttpStatus.FORBIDDEN, "PROPERTY_ACCESS_DENIED", "본인이 등록한 매물만 접근할 수 있습니다."),
    PROPERTY_REQUIRED_FIELD_MISSING(HttpStatus.BAD_REQUEST, "PROPERTY_REQUIRED_FIELD_MISSING", "필수 입력값이 누락되었습니다."),
    PROPERTY_INVALID_PRICE(HttpStatus.BAD_REQUEST, "PROPERTY_INVALID_PRICE", "거래 유형에 맞지 않는 가격 정보입니다."),
    // propertyType이 enum 타입이라 잘못된 값은 Jackson 파싱 단계(HttpMessageNotReadableException)에서
    // 걸러져 이 코드까지 도달하지 않는다 - 의도적으로 유지한다. String으로 바꿔 직접 검증하는 것보다
    // enum 타입 안전성을 유지하는 게 낫다고 판단했다(2026-07-28 property-domain-gaps-cleanup 검토).
    PROPERTY_TYPE_NOT_SUPPORTED(HttpStatus.BAD_REQUEST, "PROPERTY_TYPE_NOT_SUPPORTED", "지원하지 않는 매물 유형입니다."),
    PROPERTY_ADDRESS_RESOLUTION_FAILED(HttpStatus.UNPROCESSABLE_CONTENT, "PROPERTY_ADDRESS_RESOLUTION_FAILED", "입력한 주소를 확인할 수 없습니다."),
    PROPERTY_DUPLICATE(HttpStatus.CONFLICT, "PROPERTY_DUPLICATE", "이미 동일한 조건으로 등록된 매물이 있습니다."),
    PROPERTY_IMAGE_INVALID(HttpStatus.BAD_REQUEST, "PROPERTY_IMAGE_INVALID", "이미지 형식 또는 크기가 올바르지 않습니다."),
    PROPERTY_ALREADY_DELETED(HttpStatus.CONFLICT, "PROPERTY_ALREADY_DELETED", "이미 삭제된 매물입니다."),
    PROPERTY_INVALID_SEARCH_CONDITION(HttpStatus.BAD_REQUEST, "PROPERTY_INVALID_SEARCH_CONDITION", "검색 조건이 올바르지 않습니다."),

    // Property 신고(Report) 하위 기능
    REPORT_REASON_REQUIRED(HttpStatus.BAD_REQUEST, "REPORT_REASON_REQUIRED", "신고 사유를 선택해주세요."),
    REPORT_DETAIL_REQUIRED(HttpStatus.BAD_REQUEST, "REPORT_DETAIL_REQUIRED", "기타 사유 선택 시 상세 내용을 입력해주세요."),
    REPORT_DETAIL_TOO_LONG(HttpStatus.BAD_REQUEST, "REPORT_DETAIL_TOO_LONG", "상세 내용은 500자를 넘을 수 없습니다."),
    REPORT_DUPLICATE(HttpStatus.CONFLICT, "REPORT_DUPLICATE", "이미 신고한 매물입니다."),

    // Checklist 도메인
    CHECKLIST_NOT_FOUND(HttpStatus.NOT_FOUND, "CHECKLIST_NOT_FOUND", "체크리스트를 찾을 수 없습니다."),
    CHECKLIST_ITEM_NOT_FOUND(HttpStatus.NOT_FOUND, "CHECKLIST_ITEM_NOT_FOUND", "체크리스트 항목을 찾을 수 없습니다."),

    // Contract-Analysis 도메인
    CONTRACT_ANALYSIS_INVALID_INPUT(HttpStatus.BAD_REQUEST, "CONTRACT_ANALYSIS_INVALID_INPUT", "입력(이미지 또는 텍스트)이 없습니다."),
    CONTRACT_ANALYSIS_UNSUPPORTED_FILE_TYPE(HttpStatus.BAD_REQUEST, "CONTRACT_ANALYSIS_UNSUPPORTED_FILE_TYPE", "지원하지 않는 이미지 형식입니다."),
    CONTRACT_ANALYSIS_FILE_TOO_LARGE(HttpStatus.BAD_REQUEST, "CONTRACT_ANALYSIS_FILE_TOO_LARGE", "이미지 크기가 허용 범위를 초과했습니다."),
    CONTRACT_ANALYSIS_TEXT_TOO_SHORT(HttpStatus.BAD_REQUEST, "CONTRACT_ANALYSIS_TEXT_TOO_SHORT", "입력한 텍스트가 너무 짧습니다."),
    CONTRACT_ANALYSIS_NOT_RELATED(HttpStatus.BAD_REQUEST, "CONTRACT_ANALYSIS_NOT_RELATED", "부동산 계약과 관련이 없는 입력입니다."),
    CONTRACT_ANALYSIS_FORBIDDEN(HttpStatus.FORBIDDEN, "CONTRACT_ANALYSIS_FORBIDDEN", "본인이 등록한 매물만 계약 분석에 사용할 수 있습니다."),
    CONTRACT_ANALYSIS_OCR_EMPTY_RESULT(HttpStatus.UNPROCESSABLE_CONTENT, "CONTRACT_ANALYSIS_OCR_EMPTY_RESULT", "OCR 인식 결과가 없습니다."),
    CONTRACT_ANALYSIS_OCR_API_ERROR(HttpStatus.BAD_GATEWAY, "CONTRACT_ANALYSIS_OCR_API_ERROR", "OCR 서비스 연동 중 오류가 발생했습니다."),
    CONTRACT_ANALYSIS_MASKING_NOT_CONFIRMED(HttpStatus.BAD_REQUEST, "CONTRACT_ANALYSIS_MASKING_NOT_CONFIRMED", "마스킹 확인이 완료되지 않았습니다."),
    CONTRACT_ANALYSIS_MASKING_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "CONTRACT_ANALYSIS_MASKING_FAILED", "마스킹 처리 중 오류가 발생했습니다."),
    CONTRACT_ANALYSIS_AI_RESPONSE_INVALID(HttpStatus.BAD_GATEWAY, "CONTRACT_ANALYSIS_AI_RESPONSE_INVALID", "AI 응답 형식이 올바르지 않습니다."),
    CONTRACT_ANALYSIS_AI_HALLUCINATION(HttpStatus.BAD_GATEWAY, "CONTRACT_ANALYSIS_AI_HALLUCINATION", "AI가 입력에 없는 내용을 생성했습니다."),
    CONTRACT_ANALYSIS_AI_API_ERROR(HttpStatus.BAD_GATEWAY, "CONTRACT_ANALYSIS_AI_API_ERROR", "AI 분석 서비스 연동 중 오류가 발생했습니다."),

    // Admin 도메인
    ADMIN_USER_NOT_FOUND(HttpStatus.NOT_FOUND, "ADMIN_USER_NOT_FOUND", "존재하지 않는 사용자입니다."),
    ADMIN_INVALID_STATUS_TRANSITION(HttpStatus.CONFLICT, "ADMIN_INVALID_STATUS_TRANSITION", "허용되지 않는 상태 변경입니다."),
    ADMIN_PROPERTY_REPORT_NOT_FOUND(HttpStatus.NOT_FOUND, "ADMIN_PROPERTY_REPORT_NOT_FOUND", "존재하지 않는 신고입니다."),
    ADMIN_INVALID_DATE_RANGE(HttpStatus.BAD_REQUEST, "ADMIN_INVALID_DATE_RANGE", "조회 기간이 올바르지 않습니다."),
    ADMIN_CHECKLIST_TEMPLATE_NOT_FOUND(HttpStatus.NOT_FOUND, "ADMIN_CHECKLIST_TEMPLATE_NOT_FOUND", "존재하지 않는 체크리스트 문항입니다."),
    ADMIN_CHECKLIST_TEMPLATE_INVALID_CODE(HttpStatus.BAD_REQUEST, "ADMIN_CHECKLIST_TEMPLATE_INVALID_CODE", "이 코드는 선택한 응답 방식과 맞지 않습니다."),
    ADMIN_CHECKLIST_TEMPLATE_DUPLICATE_CODE(HttpStatus.CONFLICT, "ADMIN_CHECKLIST_TEMPLATE_DUPLICATE_CODE", "이미 다른 활성 문항이 같은 코드를 사용하고 있습니다."),
    ADMIN_CHECKLIST_TEMPLATE_LAST_ITEM(HttpStatus.CONFLICT, "ADMIN_CHECKLIST_TEMPLATE_LAST_ITEM", "마지막 문항은 삭제할 수 없습니다. 노출 여부를 꺼서 숨겨주세요."),

    // 파일 업로드(S3) 공통 - profile/property/contract 이미지 업로드가 전부 이 코드를 공유한다.
    FILE_EXTENSION_NOT_ALLOWED(HttpStatus.BAD_REQUEST, "FILE_EXTENSION_NOT_ALLOWED", "허용되지 않는 파일 확장자입니다."),
    FILE_CONTENT_TYPE_NOT_ALLOWED(HttpStatus.BAD_REQUEST, "FILE_CONTENT_TYPE_NOT_ALLOWED", "허용되지 않는 파일 형식입니다."),
    FILE_TOO_LARGE(HttpStatus.BAD_REQUEST, "FILE_TOO_LARGE", "파일 크기가 허용 범위를 초과했습니다."),
    // confirmUpload() 호출 시점에 해당 key로 S3에 실제 업로드된 객체가 없는 경우 - presigned URL만
    // 발급받고 실제 PUT은 하지 않았거나, URL 만료(5분) 후 뒤늦게 confirm을 호출한 경우다.
    FILE_UPLOAD_NOT_COMPLETED(HttpStatus.NOT_FOUND, "FILE_UPLOAD_NOT_COMPLETED", "업로드가 완료되지 않았습니다.");

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
