package com.algogyeyak.marketdata.dto;

import java.util.List;

/**
 * 매물-실거래가 시세비교 결과. property 도메인의 PropertyDetailResponse/PropertyRegisterResponse가
 * 공통으로 사용하는 공용 DTO다 (기존에는 두 응답 클래스에 동일한 레코드가 중복 정의되어 있었음).
 */
public record MarketComparisonResponse(
        String status,          // "AVAILABLE" | "UNAVAILABLE"
        Long referencePrice,     // 비교 기준가 (중앙값, 원 단위)
        Double differenceRate,   // (매물가 - 기준가) / 기준가
        Integer sampleCount,
        String referenceDate,    // 사용된 표본 중 최신 계약일 (yyyy-MM-dd)
        Integer radiusMeters,    // 실제 사용된 반경 단계 (300 또는 600) - 확장 여부를 사용자에게 안내하기 위함
        // 표본 필터링에 쓰인 면적오차 허용율(0.2 = ±20%). market-data.comparison.area-error-rate와 동일한 값
        // - 계산에 사용된 정책값을 그대로 노출해 "왜 이 표본들이 비교 대상이 됐는지" 근거를 보여주기 위함.
        // status가 UNAVAILABLE이면 null.
        Double areaErrorRate,
        // 국토부 실거래를 조회한 개월 수(market-data.comparison.lookback-months와 동일한 값). status가
        // UNAVAILABLE이면 null.
        Integer lookbackMonths,
        // 기준가(중앙값) 산출에 실제로 쓰인 개별 실거래 표본 목록(5차 멘토링 피드백 7-2) - status가
        // UNAVAILABLE이면 null, AVAILABLE이면 sampleCount와 크기가 같다.
        List<MarketTransactionSampleResponse> samples,
        String message,          // 판정불가 사유 등 사용자 안내 문구 (자유 텍스트, 화면 표시용)
        MarketComparisonUnavailableReason reason // 판정불가 사유 코드 (구조화, 다른 도메인 소비용) - AVAILABLE이면 null
) {
    public static MarketComparisonResponse unavailable(MarketComparisonUnavailableReason reason, String message) {
        return new MarketComparisonResponse(
                "UNAVAILABLE", null, null, null, null, null, null, null, null, message, reason
        );
    }

    public static MarketComparisonResponse available(
            long referencePrice, double differenceRate, int sampleCount, String referenceDate, int radiusMeters,
            double areaErrorRate, int lookbackMonths, List<MarketTransactionSampleResponse> samples
    ) {
        return new MarketComparisonResponse(
                "AVAILABLE", referencePrice, differenceRate, sampleCount, referenceDate, radiusMeters,
                areaErrorRate, lookbackMonths, samples, null, null
        );
    }
}
