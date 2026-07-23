package com.algogyeyak.checklist.dto;

import com.algogyeyak.checklist.entity.ChecklistCategory;
import com.algogyeyak.checklist.entity.ChecklistImportance;
import com.algogyeyak.checklist.entity.ChecklistItem;
import com.algogyeyak.checklist.entity.ChecklistItemType;

public record ChecklistItemResponse(
        Long id,
        ChecklistCategory category,
        String content,
        String guideText,
        ChecklistImportance importance,
        ChecklistItemType itemType,
        boolean checked,
        boolean issueFound,
        String value
) {
    public static ChecklistItemResponse from(ChecklistItem item) {
        return new ChecklistItemResponse(
                item.getId(),
                item.getCategory(),
                item.getContent(),
                item.getGuideText(),
                item.getImportance(),
                item.getItemType(),
                item.isChecked(),
                item.isIssueFound(),
                item.getValue()
        );
    }
}
