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
        // guideText/applicablePropertyTypes 둘 다 엔티티 컬럼에 length 지정이 없어 Hibernate 기본값인
        // varchar(255)로 잡힌다 - 여기서 막아두지 않으면 DB 제약 위반이 400이 아니라 500으로 올라온다.
        @Size(max = 255) String guideText,
        String helperText,
        @NotNull ChecklistImportance importance,
        @NotNull ChecklistItemType itemType,
        ChecklistItemCode code,
        @NotNull @Min(1) Integer displayOrder,
        @Size(max = 255) String applicablePropertyTypes,
        @NotNull Boolean active
) {
}
