package com.algogyeyak.marketdata.dto;

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
        String message           // 판정불가 사유 등 사용자 안내 문구
) {
    public static MarketComparisonResponse unavailable(String message) {
        return new MarketComparisonResponse("UNAVAILABLE", null, null, null, null, null, message);
    }

    public static MarketComparisonResponse available(
            long referencePrice, double differenceRate, int sampleCount, String referenceDate, int radiusMeters
    ) {
        return new MarketComparisonResponse(
                "AVAILABLE", referencePrice, differenceRate, sampleCount, referenceDate, radiusMeters, null
        );
    }
}
