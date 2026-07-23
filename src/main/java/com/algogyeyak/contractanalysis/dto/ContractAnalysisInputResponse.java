package com.algogyeyak.contractanalysis.dto;

import com.algogyeyak.contractanalysis.entity.InputType;
import com.algogyeyak.contractanalysis.entity.NextStep;

public record ContractAnalysisInputResponse(
        InputType inputType,
        boolean readyForNextStep,
        NextStep nextStep
) {
    public static ContractAnalysisInputResponse of(InputType inputType, NextStep nextStep) {
        return new ContractAnalysisInputResponse(inputType, true, nextStep);
    }
}
