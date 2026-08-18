package com.algogyeyak.riskanalysis.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

// risk-analysis가 전세가율(보증금/매매시세) 계산에 쓰는 매매 기준가 뷰. market-data의 엔티티가 아니다.
// sampleCount/radiusMeters는 "이 기준가가 몇 건/몇 m 반경 표본으로 산출됐는지"를 응답에 노출하기 위한
// 근거 데이터 - MarketSaleComparisonResponse가 이미 갖고 있던 값을 여기서도 그대로 전달한다.
public record MarketSalePrice(
        BigDecimal referencePrice,
        LocalDate referenceDate,
        Integer sampleCount,
        Integer radiusMeters
) {
}
