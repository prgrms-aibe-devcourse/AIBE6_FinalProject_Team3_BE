package com.algogyeyak.contractanalysis.client.dto;

import com.algogyeyak.contractanalysis.dto.ContractAnalysisClause;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * response_schema로 강제한 Gemini 응답 텍스트({@code candidates[0].content.parts[0].text})를
 * 파싱한 결과. Gemini가 JSON 문자열로 내려주는 최상위 스키마
 * ({@code {"summary": ..., "clauses": [...]}})와 대응된다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GeminiClauseAnalysisResult(
        String summary,
        List<ContractAnalysisClause> clauses
) {
}
