package com.algogyeyak.contractanalysis.dto;

import java.util.List;

public record ContractAnalysisChatRequest(
        ContractAnalysisChatClause clause,
        String question,
        List<ContractAnalysisChatMessage> history
) {
}
