package com.algogyeyak.contractanalysis.dto;

import com.algogyeyak.contractanalysis.entity.ContractClause;

public record ContractClauseResponse(
        Boolean riskFlag,
        String explanation,
        String question,
        String suggestedText
) {
    public static ContractClauseResponse from(ContractClause clause) {
        return new ContractClauseResponse(
                clause.getRiskFlag(),
                clause.getExplanation(),
                clause.getQuestion(),
                clause.getSuggestedText()
        );
    }
}
