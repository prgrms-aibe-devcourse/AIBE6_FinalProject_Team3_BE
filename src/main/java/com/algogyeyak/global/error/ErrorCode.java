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
    // login()에 무차별대입 방지 장치가 전혀 없어(EmailVerificationService.confirmCode()의
    // maxAttempts와 달리) 알려진 이메일에 대해 무제한 로그인 시도가 가능했던 문제를 막는다 - 이메일
    // 존재 여부와 무관하게 항상 같은 방식으로 카운트한다(계정 존재 여부 비노출 원칙 유지).
    AUTH_TOO_MANY_LOGIN_ATTEMPTS(HttpStatus.TOO_MANY_REQUESTS, "AUTH_TOO_MANY_LOGIN_ATTEMPTS", "로그인 시도가 너무 많습니다. 잠시 후 다시 시도해주세요."),
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
    // CookieUtils.SameSite=None 전환(크로스오리진 배포) 이후 최소 CSRF 방어로 추가 - CsrfHeaderFilter 참고.
    CSRF_HEADER_MISSING(HttpStatus.FORBIDDEN, "CSRF_HEADER_MISSING", "잘못된 요청입니다."),

    // 이메일 인증(회원가입) - EmailVerificationService
    // 인증번호 발송 대상 이메일이 이미 가입되어 있는 경우 - AUTH_EMAIL_ALREADY_EXISTS와 별개 코드로
    // 두는 이유는 signup() 시점(닉네임/비밀번호까지 다 입력한 뒤)이 아니라 그 전 단계(이메일만 입력한
    // 시점)에서 나는 실패라 프론트가 다른 화면 흐름으로 안내해야 하기 때문이다.
    AUTH_EMAIL_VERIFICATION_EMAIL_ALREADY_EXISTS(HttpStatus.CONFLICT, "AUTH_EMAIL_VERIFICATION_EMAIL_ALREADY_EXISTS", "이미 가입된 이메일입니다."),
    // 재발송 쿨다운(60초) 이내 재요청 - 스팸 발송 방지.
    AUTH_EMAIL_VERIFICATION_TOO_MANY_REQUESTS(HttpStatus.TOO_MANY_REQUESTS, "AUTH_EMAIL_VERIFICATION_TOO_MANY_REQUESTS", "잠시 후 다시 시도해주세요."),
    // 인증번호가 없거나(만료됨/발송한 적 없음), 틀렸거나, 시도 횟수를 초과한 경우를 모두 이 코드로
    // 통일한다 - 6자리 숫자 코드는 브루트포스 공격 표면이 작지 않으므로, 실패 사유를 세분화해 노출하면
    // 공격자가 "코드가 존재하는지"와 "시도 횟수가 얼마나 남았는지"를 구분해 알아낼 수 있다.
    AUTH_EMAIL_VERIFICATION_CODE_INVALID(HttpStatus.BAD_REQUEST, "AUTH_EMAIL_VERIFICATION_CODE_INVALID", "인증번호가 올바르지 않거나 만료되었습니다."),
    // signup() 호출 시점에 이 이메일에 대한 유효한 인증 완료 기록(Redis)이 없는 경우 - 인증번호 확인을
    // 건너뛰고 바로 회원가입을 시도했거나, 인증 유효시간(30분)이 지난 뒤 뒤늦게 가입을 완료하려는 경우.
    AUTH_EMAIL_NOT_VERIFIED(HttpStatus.FORBIDDEN, "AUTH_EMAIL_NOT_VERIFIED", "이메일 인증을 먼저 완료해주세요."),

    // 비밀번호 재설정(로그아웃 상태) - PasswordResetService
    AUTH_PASSWORD_RESET_TOO_MANY_REQUESTS(HttpStatus.TOO_MANY_REQUESTS, "AUTH_PASSWORD_RESET_TOO_MANY_REQUESTS", "잠시 후 다시 시도해주세요."),
    // 토큰이 없거나(만료/미발급), 이미 사용됐거나, 존재하지 않는 경우를 모두 이 코드로 통일한다 -
    // AUTH_REFRESH_TOKEN_INVALID와 같은 이유(Redis TTL 자연 만료와 미발급을 구분할 수 없음, 계정
    // 존재 여부를 굳이 세분화해 노출할 필요 없음).
    AUTH_PASSWORD_RESET_TOKEN_INVALID(HttpStatus.BAD_REQUEST, "AUTH_PASSWORD_RESET_TOKEN_INVALID", "유효하지 않거나 만료된 링크입니다."),
    // SMTP 발송 실패(EmailService) - 외부 서비스 연동 실패라 CONTRACT_ANALYSIS_*_API_ERROR와 같은
    // 이유로 502를 쓴다. Redis(코드/토큰 발급 자체)는 이미 성공한 뒤일 수 있어, 재시도 시 쿨다운에
    // 걸릴 수 있음을 프론트가 안내해야 한다.
    EMAIL_SEND_FAILED(HttpStatus.BAD_GATEWAY, "EMAIL_SEND_FAILED", "메일 발송에 실패했습니다. 잠시 후 다시 시도해주세요."),

    // User 도메인
    // getActiveUserOrThrow()에서 "존재하지 않음"(공용 NOT_FOUND)과 구분해서 던진다 - 셋 다 404라
    // 상태 코드는 같지만, code/message가 달라야 프론트가 탈퇴/정지 상태를 구분해 안내할 수 있다.
    USER_WITHDRAWN(HttpStatus.NOT_FOUND, "USER_WITHDRAWN", "이미 탈퇴한 사용자입니다."),
    USER_SUSPENDED(HttpStatus.NOT_FOUND, "USER_SUSPENDED", "정지된 사용자입니다."),
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
    CONTRACT_ANALYSIS_FORBIDDEN(HttpStatus.FORBIDDEN, "CONTRACT_ANALYSIS_FORBIDDEN", "본인이 등록한 매물만 계약 분석에 사용할 수 있습니다."),
    CONTRACT_ANALYSIS_OCR_EMPTY_RESULT(HttpStatus.UNPROCESSABLE_CONTENT, "CONTRACT_ANALYSIS_OCR_EMPTY_RESULT", "OCR 인식 결과가 없습니다."),
    CONTRACT_ANALYSIS_OCR_API_ERROR(HttpStatus.BAD_GATEWAY, "CONTRACT_ANALYSIS_OCR_API_ERROR", "OCR 서비스 연동 중 오류가 발생했습니다."),
    CONTRACT_ANALYSIS_MASKING_NOT_CONFIRMED(HttpStatus.BAD_REQUEST, "CONTRACT_ANALYSIS_MASKING_NOT_CONFIRMED", "마스킹 확인이 완료되지 않았습니다."),
    CONTRACT_ANALYSIS_UNMASKED_PII_DETECTED(HttpStatus.BAD_REQUEST, "CONTRACT_ANALYSIS_UNMASKED_PII_DETECTED", "마스킹되지 않은 개인정보가 포함되어 있습니다."),
    CONTRACT_ANALYSIS_MASKING_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "CONTRACT_ANALYSIS_MASKING_FAILED", "마스킹 처리 중 오류가 발생했습니다."),
    CONTRACT_ANALYSIS_AI_RESPONSE_INVALID(HttpStatus.BAD_GATEWAY, "CONTRACT_ANALYSIS_AI_RESPONSE_INVALID", "AI 응답 형식이 올바르지 않습니다."),
    CONTRACT_ANALYSIS_AI_HALLUCINATION(HttpStatus.BAD_GATEWAY, "CONTRACT_ANALYSIS_AI_HALLUCINATION", "AI가 입력에 없는 내용을 생성했습니다."),
    CONTRACT_ANALYSIS_AI_API_ERROR(HttpStatus.BAD_GATEWAY, "CONTRACT_ANALYSIS_AI_API_ERROR", "AI 분석 서비스 연동 중 오류가 발생했습니다."),
    CONTRACT_ANALYSIS_QUESTION_REQUIRED(HttpStatus.BAD_REQUEST, "CONTRACT_ANALYSIS_QUESTION_REQUIRED", "질문을 입력해주세요."),

    // Admin 도메인
    ADMIN_USER_NOT_FOUND(HttpStatus.NOT_FOUND, "ADMIN_USER_NOT_FOUND", "존재하지 않는 사용자입니다."),
    ADMIN_INVALID_STATUS_TRANSITION(HttpStatus.CONFLICT, "ADMIN_INVALID_STATUS_TRANSITION", "허용되지 않는 상태 변경입니다."),
    ADMIN_INVALID_ROLE_TRANSITION(HttpStatus.CONFLICT, "ADMIN_INVALID_ROLE_TRANSITION", "허용되지 않는 권한 변경입니다."),
    ADMIN_LAST_ADMIN_ACCOUNT(HttpStatus.CONFLICT, "ADMIN_LAST_ADMIN_ACCOUNT", "마지막 남은 관리자 계정은 강등하거나 정지할 수 없습니다."),
    ADMIN_PROPERTY_REPORT_NOT_FOUND(HttpStatus.NOT_FOUND, "ADMIN_PROPERTY_REPORT_NOT_FOUND", "존재하지 않는 신고입니다."),
    ADMIN_PROPERTY_REPORT_SELF_REVIEW(HttpStatus.CONFLICT, "ADMIN_PROPERTY_REPORT_SELF_REVIEW", "본인이 등록한 신고는 직접 처리할 수 없습니다."),
    ADMIN_INVALID_DATE_RANGE(HttpStatus.BAD_REQUEST, "ADMIN_INVALID_DATE_RANGE", "조회 기간이 올바르지 않습니다."),
    ADMIN_CHECKLIST_TEMPLATE_NOT_FOUND(HttpStatus.NOT_FOUND, "ADMIN_CHECKLIST_TEMPLATE_NOT_FOUND", "존재하지 않는 체크리스트 문항입니다."),
    ADMIN_CHECKLIST_TEMPLATE_INVALID_CODE(HttpStatus.BAD_REQUEST, "ADMIN_CHECKLIST_TEMPLATE_INVALID_CODE", "이 코드는 선택한 응답 방식과 맞지 않습니다."),
    ADMIN_CHECKLIST_TEMPLATE_DUPLICATE_CODE(HttpStatus.CONFLICT, "ADMIN_CHECKLIST_TEMPLATE_DUPLICATE_CODE", "이미 다른 활성 문항이 같은 코드를 사용하고 있습니다."),
    ADMIN_CHECKLIST_TEMPLATE_LAST_ITEM(HttpStatus.CONFLICT, "ADMIN_CHECKLIST_TEMPLATE_LAST_ITEM", "마지막 문항은 삭제할 수 없습니다. 노출 여부를 꺼서 숨겨주세요."),
    ADMIN_CHECKLIST_TEMPLATE_INVALID_PROPERTY_TYPE(HttpStatus.BAD_REQUEST, "ADMIN_CHECKLIST_TEMPLATE_INVALID_PROPERTY_TYPE", "존재하지 않는 매물유형입니다."),
    ADMIN_CHECKLIST_TEMPLATE_IMAGE_NOT_FOUND(HttpStatus.NOT_FOUND, "ADMIN_CHECKLIST_TEMPLATE_IMAGE_NOT_FOUND", "존재하지 않는 이미지입니다."),

    // 파일 업로드(S3) 공통 - profile/property/contract 이미지 업로드가 전부 이 코드를 공유한다.
    FILE_EXTENSION_NOT_ALLOWED(HttpStatus.BAD_REQUEST, "FILE_EXTENSION_NOT_ALLOWED", "허용되지 않는 파일 확장자입니다."),
    FILE_CONTENT_TYPE_NOT_ALLOWED(HttpStatus.BAD_REQUEST, "FILE_CONTENT_TYPE_NOT_ALLOWED", "허용되지 않는 파일 형식입니다."),
    FILE_TOO_LARGE(HttpStatus.BAD_REQUEST, "FILE_TOO_LARGE", "파일 크기가 허용 범위를 초과했습니다."),
    // confirmUpload() 호출 시점에 해당 key로 S3에 실제 업로드된 객체가 없는 경우 - presigned URL만
    // 발급받고 실제 PUT은 하지 않았거나, URL 만료(5분) 후 뒤늦게 confirm을 호출한 경우다.
    FILE_UPLOAD_NOT_COMPLETED(HttpStatus.NOT_FOUND, "FILE_UPLOAD_NOT_COMPLETED", "업로드가 완료되지 않았습니다."),
    // 클라이언트가 넘긴 key가 본인 소유 prefix(예: profile-images/{본인 userId}/...)가 아닌 경우 -
    // 다른 사용자나 다른 도메인(property-images/, contract-images/)의 key를 그대로 넘겨 confirm을
    // 시도하는 것을 막는다.
    FILE_KEY_ACCESS_DENIED(HttpStatus.FORBIDDEN, "FILE_KEY_ACCESS_DENIED", "본인이 업로드한 파일만 확인할 수 있습니다.");

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
