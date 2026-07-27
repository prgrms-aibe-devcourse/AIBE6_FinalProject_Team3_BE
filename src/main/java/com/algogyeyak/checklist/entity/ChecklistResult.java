package com.algogyeyak.checklist.entity;

/**
 * 체크리스트 결과 확인 응답용 값 객체. 등급/점수 없이 개수로만 표현한다.
 * status가 NOT_STARTED일 때만 message에 시작 안내 문구가 채워지고, 그 외에는 null이다.
 */
public record ChecklistResult(
        ChecklistStatus status,
        int checkedCount,
        int totalCount,
        int requiredMissingCount,
        int issueCount,
        String message
) {
}
