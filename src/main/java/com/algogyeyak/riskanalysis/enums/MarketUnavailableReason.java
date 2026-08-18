package com.algogyeyak.riskanalysis.enums;

public enum MarketUnavailableReason {
    INSUFFICIENT_SAMPLE,      // 600m 확장해도 표본 3건 미만
    ADDRESS_INFO_MISSING,     // 주소 정보 부족
    PROPERTY_TYPE_UNSUPPORTED, // 주택 유형 정보 부족(단독/다가구 등 실거래 위치 비공개)
    // (2026-08-14 신규) 거래유형(월세) 미지원 - 예전엔 PROPERTY_TYPE_UNSUPPORTED로 뭉뚱그려 매핑돼
    // 월세 매물인데 "매물 유형을 지원하지 않는다"는 부정확한 사유가 내려갔다(risk-analysis-design.md
    // 전수조사 결과 버그 2번).
    TRANSACTION_TYPE_UNSUPPORTED,
    EXTERNAL_API_FAILURE,     // 외부 API 장애/타임아웃
    RATE_LIMIT_EXCEEDED,      // 호출 제한 초과
    INVALID_RESPONSE_FORMAT   // 응답 형식 오류
}
