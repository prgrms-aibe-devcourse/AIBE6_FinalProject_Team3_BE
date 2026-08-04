package com.algogyeyak.checklist.dto;

import com.algogyeyak.checklist.entity.ChecklistCategory;
import com.algogyeyak.checklist.entity.ChecklistImportance;
import com.algogyeyak.checklist.entity.ChecklistItemCode;
import com.algogyeyak.checklist.entity.ChecklistItemType;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * version은 요청에서 받지 않는다 - 서비스가 현재 존재하는 문항들 중 가장 높은 버전으로 자동 배정한다
 * (신규 문항이 기존 활성 문항과 같은 버전으로 취급되도록).
 */
public record AdminChecklistItemTemplateCreateRequest(
        @NotNull ChecklistCategory category,
        @NotBlank @Size(max = 200) String content,
        String guideText,
        String helperText,
        @NotNull ChecklistImportance importance,
        @NotNull ChecklistItemType itemType,
        ChecklistItemCode code,
        @NotNull @Min(1) Integer displayOrder,
        String applicablePropertyTypes
) {
}
