package com.algogyeyak.checklist.dto;

import com.algogyeyak.checklist.entity.ChecklistResult;
import com.algogyeyak.checklist.entity.ChecklistStatus;
import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChecklistResultResponse(
        ChecklistStatus status,
        int checkedCount,
        int totalCount,
        int requiredMissingCount,
        int issueCount,
        String message
) {
    public static ChecklistResultResponse from(ChecklistResult result) {
        return new ChecklistResultResponse(
                result.status(),
                result.checkedCount(),
                result.totalCount(),
                result.requiredMissingCount(),
                result.issueCount(),
                result.message()
        );
    }
}
