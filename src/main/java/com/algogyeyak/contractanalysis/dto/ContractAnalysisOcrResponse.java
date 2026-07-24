package com.algogyeyak.contractanalysis.dto;

public record ContractAnalysisOcrResponse(
        String extractedText,
        double confidence,
        boolean editable
) {
    public static ContractAnalysisOcrResponse of(String extractedText, double confidence) {
        return new ContractAnalysisOcrResponse(extractedText, confidence, true);
    }
}
