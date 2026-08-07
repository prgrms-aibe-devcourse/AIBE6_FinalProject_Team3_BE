package com.algogyeyak.contractanalysis.dto;

/**
 * @param role "user" 또는 "assistant"
 */
public record ContractAnalysisChatMessage(
        String role,
        String content
) {
}
