package com.algogyeyak.riskanalysis.enums;

public enum DepositSafetyCheckReason {
    // UNAVAILABLE (판정불가)
    ESTIMATED_PRICE_MISSING,      // 추정 매매시세 없음 (실거래가 데이터 부족 포함)
    DEPOSIT_INFO_MISSING,         // 보증금 정보 없음
    TRANSACTION_TYPE_UNSUPPORTED, // 분석 대상이 아닌 거래 유형 (월세)
    // FAILED (실패)
    CALCULATION_DATA_INVALID,     // 잘못된 계산 데이터
    INTERNAL_ERROR                // 내부 계산 오류
}
