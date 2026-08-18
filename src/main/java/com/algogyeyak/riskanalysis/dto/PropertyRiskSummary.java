package com.algogyeyak.riskanalysis.dto;

// property 도메인의 매물 목록 배지(위험신호 개수·전세가율)용 요약 DTO. PropertyRiskSummaryProvider가
// property.service.PropertyService에게 내려주는 유일한 형태 - property는 이 DTO 외에 risk-analysis의
// 엔티티/리포지토리를 몰라도 된다.
public record PropertyRiskSummary(
        Integer checkSignalCount, // risk-analysis를 한 번도 안 돌렸으면 null, 돌렸는데 0건이면 0
        String signalSummary,     // checkSignalCount가 1 이상일 때만 값 존재
        Integer jeonseRatio        // DepositSafetyCheck.status가 CALCULATED일 때만 값 존재
) {}
