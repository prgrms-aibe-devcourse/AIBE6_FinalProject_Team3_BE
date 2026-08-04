package com.algogyeyak.riskanalysis.dto;

import java.time.LocalDateTime;

/**
 * 위험 신호 분석 실행(POST /risk-analysis) 응답. 신호 상세 목록은 GET /risk-signals가 담당하므로,
 * 여기서는 "확인 필요 신호가 몇 건이었는지" 요약만 제공한다.
 */
public record RiskAnalysisSummaryResponse(
        int signalCount,
        String policyVersion,
        LocalDateTime calculatedAt
) {
}
