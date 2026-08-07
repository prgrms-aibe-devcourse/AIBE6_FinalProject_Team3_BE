package com.algogyeyak.contractanalysis.dto;

/**
 * /chat 요청에서 질의 대상이 되는 조항. /analyze 응답의 {@link ContractAnalysisClause}와 달리
 * question/suggestedText는 챗봇 대화 맥락에 필요하지 않아 제외한다.
 */
public record ContractAnalysisChatClause(
        String originalText,
        Boolean riskFlag,
        String explanation
) {
}
