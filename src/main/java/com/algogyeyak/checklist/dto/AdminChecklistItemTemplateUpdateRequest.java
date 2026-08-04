package com.algogyeyak.checklist.dto;

import com.algogyeyak.checklist.entity.ChecklistCategory;
import com.algogyeyak.checklist.entity.ChecklistImportance;
import com.algogyeyak.checklist.entity.ChecklistItemCode;
import com.algogyeyak.checklist.entity.ChecklistItemType;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record AdminChecklistItemTemplateUpdateRequest(
        @NotNull ChecklistCategory category,
        @NotBlank @Size(max = 200) String content,
        String guideText,
        String helperText,
        @NotNull ChecklistImportance importance,
        @NotNull ChecklistItemType itemType,
        ChecklistItemCode code,
        @NotNull @Min(1) Integer displayOrder,
        String applicablePropertyTypes,
        @NotNull Boolean active
) {
}
