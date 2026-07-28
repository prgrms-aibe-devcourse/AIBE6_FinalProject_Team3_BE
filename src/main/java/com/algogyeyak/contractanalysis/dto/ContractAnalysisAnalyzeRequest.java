package com.algogyeyak.contractanalysis.dto;

public record ContractAnalysisAnalyzeRequest(
        String maskedText,
        Boolean userConfirmed,
        Long propertyId
) {
}
