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
    INSUFFICIENT_SAMPLE,          // 반경 확장 후에도 비교 가능한 실거래 표본이 부족함
    // (2026-08-20 신규) 매물 목록 조회는 캐시된 결과만 읽고 계산을 트리거하지 않는다
    // (MarketComparisonService.getCachedOnly() 참고) - 아직 한 번도 계산된 적 없거나 캐시가
    // 만료된 매물이면 이 사유로 응답한다. compare()가 직접 만드는 사유가 아니라 실제 계산
    // 실패와는 무관하다 - 상세조회/등록/수정에서 곧 채워질 예정이라는 뜻.
    NOT_YET_CALCULATED
}
