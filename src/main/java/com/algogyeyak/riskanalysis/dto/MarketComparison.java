package com.algogyeyak.riskanalysis.dto;

import com.algogyeyak.riskanalysis.enums.MarketUnavailableReason;

import java.math.BigDecimal;
import java.time.LocalDate;

// risk-analysis가 실제로 쓰는 DTO (market-data의 내부 엔티티가 아님)
public record MarketComparison(
        Long propertyId,
        BigDecimal referencePrice,   // status=SUCCESS일 때만 값 존재
        BigDecimal askingPrice,
        BigDecimal differenceRate,
        int sampleCount,
        LocalDate referenceDate,
        Integer appliedRadius,       // 300 또는 600, null 가능 (UNAVAILABLE/FAILED 시)
        MarketUnavailableReason reason,               // UNAVAILABLE/FAILED 사유 코드, SUCCESS면 null
        MarketComparisonStatus status
) {}
