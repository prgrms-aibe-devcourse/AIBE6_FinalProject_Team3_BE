package com.algogyeyak.contractanalysis.dto;

import com.algogyeyak.contractanalysis.entity.ContractRequest;
import com.algogyeyak.contractanalysis.entity.InputType;
import java.time.LocalDateTime;
import java.util.List;

public record ContractHistoryDetailResponse(
        Long id,
        Long propertyId,
        String propertyTitle,
        InputType inputType,
        String summary,
        int clauseCount,
        int riskCount,
        String disclaimer,
        String aiGeneratedNotice,
        String status,
        LocalDateTime createdAt,
        List<ContractClauseResponse> clauses
) {
    // propertyTitle은 별도 조회(Property 조인)로만 채울 수 있어 인자로 받는다 - propertyId가 null이거나
    // (매물과 연결되지 않은 분석 이력), 연결된 매물이 이미 삭제/조회 불가면 null로 내려간다.
    public static ContractHistoryDetailResponse from(ContractRequest contractRequest, String propertyTitle) {
        return new ContractHistoryDetailResponse(
                contractRequest.getId(),
                contractRequest.getPropertyId(),
                propertyTitle,
                contractRequest.getInputType(),
                contractRequest.getSummary(),
                contractRequest.getClauseCount(),
                contractRequest.getRiskCount(),
                contractRequest.getDisclaimer(),
                contractRequest.getAiGeneratedNotice(),
                contractRequest.getStatus().name(),
                contractRequest.getCreatedAt(),
                contractRequest.getClauses().stream().map(ContractClauseResponse::from).toList()
        );
    }
}
