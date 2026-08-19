package com.algogyeyak.contractanalysis.dto;

import com.algogyeyak.contractanalysis.entity.ContractRequest;
import com.algogyeyak.contractanalysis.entity.InputType;
import java.time.LocalDateTime;
import java.util.List;

public record ContractHistoryDetailResponse(
        Long id,
        Long propertyId,
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
    public static ContractHistoryDetailResponse from(ContractRequest contractRequest) {
        return new ContractHistoryDetailResponse(
                contractRequest.getId(),
                contractRequest.getPropertyId(),
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
