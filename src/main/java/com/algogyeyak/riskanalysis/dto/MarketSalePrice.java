package com.algogyeyak.riskanalysis.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

// risk-analysis가 전세가율(보증금/매매시세) 계산에 쓰는 매매 기준가 뷰. market-data의 엔티티가 아니다.
public record MarketSalePrice(
        BigDecimal referencePrice,
        LocalDate referenceDate
) {
}
