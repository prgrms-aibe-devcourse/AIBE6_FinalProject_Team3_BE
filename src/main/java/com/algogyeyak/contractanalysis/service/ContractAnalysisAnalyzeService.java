package com.algogyeyak.contractanalysis.service;

import com.algogyeyak.contractanalysis.client.GeminiClient;
import com.algogyeyak.contractanalysis.client.dto.GeminiClauseAnalysisResult;
import com.algogyeyak.contractanalysis.client.dto.GeminiGenerateContentResponse;
import com.algogyeyak.contractanalysis.dto.ContractAnalysisAnalyzeRequest;
import com.algogyeyak.contractanalysis.dto.ContractAnalysisAnalyzeResponse;
import com.algogyeyak.contractanalysis.dto.ContractAnalysisClause;
import com.algogyeyak.global.error.ErrorCode;
import com.algogyeyak.global.exception.BusinessException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class ContractAnalysisAnalyzeService {

    private final GeminiClient geminiClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ContractAnalysisAnalyzeService(GeminiClient geminiClient) {
        this.geminiClient = geminiClient;
    }

    public ContractAnalysisAnalyzeResponse analyze(ContractAnalysisAnalyzeRequest request) {
        if (!Boolean.TRUE.equals(request.userConfirmed())) {
            throw new BusinessException(ErrorCode.CONTRACT_ANALYSIS_MASKING_NOT_CONFIRMED);
        }
        if (!StringUtils.hasText(request.maskedText())) {
            throw new BusinessException(ErrorCode.CONTRACT_ANALYSIS_INVALID_INPUT);
        }

        GeminiGenerateContentResponse response = geminiClient.analyzeClauses(request.maskedText());
        String responseText = extractResponseText(response);
        List<ContractAnalysisClause> clauses = parseAndValidateSchema(responseText);
        validateNoHallucination(clauses, request.maskedText());

        return ContractAnalysisAnalyzeResponse.of(clauses);
    }

    private String extractResponseText(GeminiGenerateContentResponse response) {
        if (response == null || response.candidates() == null || response.candidates().isEmpty()) {
            throw new BusinessException(ErrorCode.CONTRACT_ANALYSIS_AI_RESPONSE_INVALID);
        }

        GeminiGenerateContentResponse.Content content = response.candidates().get(0).content();
        if (content == null || content.parts() == null || content.parts().isEmpty()) {
            throw new BusinessException(ErrorCode.CONTRACT_ANALYSIS_AI_RESPONSE_INVALID);
        }

        String text = content.parts().get(0).text();
        if (!StringUtils.hasText(text)) {
            throw new BusinessException(ErrorCode.CONTRACT_ANALYSIS_AI_RESPONSE_INVALID);
        }

        return text;
    }

    private List<ContractAnalysisClause> parseAndValidateSchema(String responseText) {
        GeminiClauseAnalysisResult result;
        try {
            result = objectMapper.readValue(responseText, GeminiClauseAnalysisResult.class);
        } catch (JsonProcessingException e) {
            throw new BusinessException(ErrorCode.CONTRACT_ANALYSIS_AI_RESPONSE_INVALID);
        }

        if (result == null || result.clauses() == null) {
            throw new BusinessException(ErrorCode.CONTRACT_ANALYSIS_AI_RESPONSE_INVALID);
        }

        for (ContractAnalysisClause clause : result.clauses()) {
            if (!isSchemaValid(clause)) {
                throw new BusinessException(ErrorCode.CONTRACT_ANALYSIS_AI_RESPONSE_INVALID);
            }
        }

        return result.clauses();
    }

    private boolean isSchemaValid(ContractAnalysisClause clause) {
        return clause != null
                && StringUtils.hasText(clause.originalText())
                && clause.riskFlag() != null
                && StringUtils.hasText(clause.explanation())
                && StringUtils.hasText(clause.question())
                && StringUtils.hasText(clause.suggestedText());
    }

    // AI가 입력에 없는 조항을 지어내는 것(환각)을 막기 위해, 반환된 조항의 원문이
    // 실제로 마스킹된 입력 텍스트 안에 그대로 존재하는지 확인한다.
    private void validateNoHallucination(List<ContractAnalysisClause> clauses, String maskedText) {
        for (ContractAnalysisClause clause : clauses) {
            if (!maskedText.contains(clause.originalText())) {
                throw new BusinessException(ErrorCode.CONTRACT_ANALYSIS_AI_HALLUCINATION);
            }
        }
    }
}
