package com.algogyeyak.riskanalysis.enums;

public enum MarketUnavailableReason {
    INSUFFICIENT_SAMPLE,      // 600m 확장해도 표본 3건 미만
    ADDRESS_INFO_MISSING,     // 주소 정보 부족
    PROPERTY_TYPE_UNSUPPORTED, // 주택 유형 정보 부족
    EXTERNAL_API_FAILURE,     // 외부 API 장애/타임아웃
    RATE_LIMIT_EXCEEDED,      // 호출 제한 초과
    INVALID_RESPONSE_FORMAT   // 응답 형식 오류
}
