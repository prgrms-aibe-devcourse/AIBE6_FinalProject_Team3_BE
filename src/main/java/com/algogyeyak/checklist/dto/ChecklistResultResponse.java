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
        String message,
        String disclaimer
) {
    // 체크리스트 결과가 매물의 안전을 보장하는 판정이 아니라는 고지 - 상태와 무관하게 항상 동일하게 붙는다.
    private static final String SAFETY_DISCLAIMER = "이 결과는 매물의 안전을 보장하지 않습니다.";

    public static ChecklistResultResponse from(ChecklistResult result) {
        return new ChecklistResultResponse(
                result.status(),
                result.checkedCount(),
                result.totalCount(),
                result.requiredMissingCount(),
                result.issueCount(),
                result.message(),
                SAFETY_DISCLAIMER
        );
    }
}
