package com.algogyeyak.marketdata.dto;

/**
 * 시세비교(MarketComparisonResponse) 기준가 산출에 실제로 사용된 개별 실거래 표본 1건.
 * 국토부 실거래가 공개시스템이 원래도 공개하는 데이터(신고 의무가 있는 공공데이터)를 그대로 노출하는
 * 것이라 별도의 개인정보 이슈는 없다 - MarketComparisonService.compare()가 지오코딩·반경 필터까지
 * 통과해 실제로 중앙값 계산에 쓴 표본만 담는다(5차 멘토링 피드백 7-2, "어떤 거래를 근거로 이 가격이
 * 나온 건지 사용자가 직접 확인할 수 없다"는 지적에 대한 응답).
 */
public record MarketTransactionSampleResponse(
        // 오피스텔/연립다세대명. 국토부 API가 건물명을 안 주는 경우도 있어 null 가능.
        String buildingName,
        // 시군구+법정동+지번 조합 주소 (MarketComparisonService.geocodeAll()이 지오코딩에 쓰는 것과 동일한 조합).
        String address,
        String dealDate,     // yyyy-MM-dd
        Long depositWon,     // 보증금(전세금), 원 단위
        Double areaSqm       // 전용면적
) {
}
