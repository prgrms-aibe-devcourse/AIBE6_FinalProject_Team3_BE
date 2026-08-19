package com.algogyeyak.riskanalysis.enums;

public enum RiskCheckReason {
    // UNDETERMINABLE
    NO_COMPARABLE_TRANSACTION,   // 비교 가능한 실거래 데이터 없음
    ADDRESS_INFO_MISSING,        // 주소 정보 부족
    PROPERTY_TYPE_UNSUPPORTED,   // 주택 유형 정보 부족
    TRANSACTION_TYPE_UNSUPPORTED, // 거래유형(월세) 미지원 - PROPERTY_TYPE_UNSUPPORTED와 구분
    // FAILED
    POLICY_CALCULATION_ERROR,    // 계산 정책 오류
    DATA_FETCH_FAILURE,          // 데이터 조회 실패
    INTERNAL_ERROR                // 내부 처리 오류
}
