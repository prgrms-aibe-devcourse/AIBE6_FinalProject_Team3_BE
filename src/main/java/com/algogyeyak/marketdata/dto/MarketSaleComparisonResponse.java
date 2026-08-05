package com.algogyeyak.marketdata.dto;

/**
 * 매물-매매 실거래가 시세비교 결과. risk-analysis의 전세가율(보증금/매매시세) 계산 전용으로 쓰이며,
 * {@link MarketComparisonResponse}(전세 시세비교, 매물 자체 가격과의 차이율 포함)와 달리 매물의
 * 거래유형과 무관하게 "이 매물의 매매 기준가가 얼마인지"만 조회한다 - differenceRate가 없는 이유.
 */
public record MarketSaleComparisonResponse(
        String status,          // "AVAILABLE" | "UNAVAILABLE"
        Long referencePrice,     // 매매 기준가 (중앙값, 원 단위)
        Integer sampleCount,
        String referenceDate,    // 사용된 표본 중 최신 계약일 (yyyy-MM-dd)
        Integer radiusMeters,    // 실제 사용된 반경 단계 (300 또는 600)
        String message,
        MarketComparisonUnavailableReason reason
) {
    public static MarketSaleComparisonResponse unavailable(MarketComparisonUnavailableReason reason, String message) {
        return new MarketSaleComparisonResponse("UNAVAILABLE", null, null, null, null, message, reason);
    }

    public static MarketSaleComparisonResponse available(
            long referencePrice, int sampleCount, String referenceDate, int radiusMeters
    ) {
        return new MarketSaleComparisonResponse(
                "AVAILABLE", referencePrice, sampleCount, referenceDate, radiusMeters, null, null
        );
    }
}
