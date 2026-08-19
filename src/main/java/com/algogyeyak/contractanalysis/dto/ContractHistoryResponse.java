package com.algogyeyak.contractanalysis.dto;

import com.algogyeyak.contractanalysis.entity.ContractRequest;
import com.algogyeyak.contractanalysis.entity.InputType;
import java.time.LocalDateTime;

public record ContractHistoryResponse(
        Long id,
        Long propertyId,
        InputType inputType,
        String summary,
        int clauseCount,
        int riskCount,
        String status,
        LocalDateTime createdAt
) {
    public static ContractHistoryResponse from(ContractRequest contractRequest) {
        return new ContractHistoryResponse(
                contractRequest.getId(),
                contractRequest.getPropertyId(),
                contractRequest.getInputType(),
                contractRequest.getSummary(),
                contractRequest.getClauseCount(),
                contractRequest.getRiskCount(),
                contractRequest.getStatus().name(),
                contractRequest.getCreatedAt()
        );
    }
}
