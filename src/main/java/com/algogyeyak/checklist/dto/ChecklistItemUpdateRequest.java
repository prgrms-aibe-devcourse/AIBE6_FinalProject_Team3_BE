package com.algogyeyak.checklist.dto;

/**
 * PATCH /checklists/{checklistId}/items/{itemId} 요청 바디.
 * checked/value/userNote 중 정확히 하나만 채워서 보낸다 (discriminated union).
 * - checked: CHECK 타입의 완료/미확인 전환
 * - value: YES_NO/DATE/DOCUMENT_REQUEST 답변
 * - userNote: CHECK 타입의 "미흡" 표시 + 메모 (빈 문자열 허용)
 */
public record ChecklistItemUpdateRequest(
        Boolean checked,
        String value,
        String userNote
) {
}
