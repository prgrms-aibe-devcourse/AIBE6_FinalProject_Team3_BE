package com.algogyeyak.contractanalysis.dto;

import com.algogyeyak.contractanalysis.entity.InputType;

public record ContractAnalysisAnalyzeRequest(
        String maskedText,
        Boolean userConfirmed,
        Long propertyId,
        InputType inputType
) {
}
