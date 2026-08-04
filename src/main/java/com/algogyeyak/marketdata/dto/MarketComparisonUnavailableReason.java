package com.algogyeyak.marketdata.dto;

/**
 * 시세비교가 UNAVAILABLE인 이유를 구조화한 코드. 기존 message(자유 텍스트, 사용자 안내용)와 별개로,
 * risk-analysis 같은 다른 도메인이 프로그램적으로 안전하게 분기할 수 있도록 존재한다 - message 문구가
 * 나중에 바뀌어도 이 값은 코드 계약이라 깨지지 않는다.
 *
 * 지금은 UNAVAILABLE(판정 불가류) 사유만 다룬다. 국토부/카카오 API 자체가 실패하는 경우
 * (EXTERNAL_API_FAILURE 등, risk-analysis의 FAILED에 대응)는 MolitRentClientImpl 등이 예외를
 * 던지지 않고 빈 리스트로 삼켜버려 지금 구조로는 구분이 안 되므로, 이번 범위에서는 다루지 않는다.
 */
public enum MarketComparisonUnavailableReason {
    TRANSACTION_TYPE_UNSUPPORTED, // 월세 등 시세 비교 대상이 아닌 거래유형
    PROPERTY_TYPE_UNSUPPORTED,    // 단독/다가구 등 실거래 위치 비공개로 비교 불가한 매물유형
    ADDRESS_INFO_MISSING,         // 좌표 없음, 지역코드 조회 실패 등 주소 관련 정보 부족
    INSUFFICIENT_SAMPLE           // 반경 확장 후에도 비교 가능한 실거래 표본이 부족함
}
