package com.algogyeyak.contractanalysis.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Gemini의 response_schema로 강제한 조항별 분석 결과. 그대로 API 응답에도 재사용한다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ContractAnalysisClause(
        String originalText,
        Boolean riskFlag,
        String explanation,
        String question,
        String suggestedText
) {
}
