package com.algogyeyak.checklist.dto;

import com.algogyeyak.checklist.entity.ChecklistCategory;
import com.algogyeyak.checklist.entity.ChecklistImportance;
import com.algogyeyak.checklist.entity.ChecklistItemCode;
import com.algogyeyak.checklist.entity.ChecklistItemTemplate;
import com.algogyeyak.checklist.entity.ChecklistItemType;

public record AdminChecklistItemTemplateResponse(
        Long id,
        int version,
        ChecklistItemCode code,
        ChecklistCategory category,
        String content,
        String guideText,
        String helperText,
        ChecklistImportance importance,
        ChecklistItemType itemType,
        String options,
        int displayOrder,
        boolean active,
        String applicablePropertyTypes
) {
    public static AdminChecklistItemTemplateResponse from(ChecklistItemTemplate template) {
        return new AdminChecklistItemTemplateResponse(
                template.getId(),
                template.getVersion(),
                template.getCode(),
                template.getCategory(),
                template.getContent(),
                template.getGuideText(),
                template.getHelperText(),
                template.getImportance(),
                template.getItemType(),
                template.getOptions(),
                template.getDisplayOrder(),
                template.isActive(),
                template.getApplicablePropertyTypes()
        );
    }
}
