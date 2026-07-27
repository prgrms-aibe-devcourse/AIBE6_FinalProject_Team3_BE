package com.algogyeyak.contractanalysis.dto;

public record ContractAnalysisMaskingResponse(
        String maskedText,
        int maskedCount,
        boolean requiresUserConfirmation
) {
    public static ContractAnalysisMaskingResponse of(String maskedText, int maskedCount) {
        return new ContractAnalysisMaskingResponse(maskedText, maskedCount, true);
    }
}
