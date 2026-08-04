package com.algogyeyak.contractanalysis.dto;

import java.util.List;

public record ContractAnalysisOcrResponse(
        String extractedText,
        double confidence,
        boolean editable,
        List<UncertainField> uncertainFields
) {
    public static ContractAnalysisOcrResponse of(
            String extractedText,
            double confidence,
            List<UncertainField> uncertainFields
    ) {
        return new ContractAnalysisOcrResponse(extractedText, confidence, true, uncertainFields);
    }

    public record UncertainField(String text, int index) {
    }
}
