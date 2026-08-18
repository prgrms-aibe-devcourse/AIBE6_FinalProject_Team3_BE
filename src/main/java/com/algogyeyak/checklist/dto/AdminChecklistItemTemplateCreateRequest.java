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
        // guideText/applicablePropertyTypes 둘 다 엔티티 컬럼에 length 지정이 없어 Hibernate 기본값인
        // varchar(255)로 잡힌다 - 여기서 막아두지 않으면 DB 제약 위반이 400이 아니라 500으로 올라온다.
        @Size(max = 255) String guideText,
        String helperText,
        @NotNull ChecklistImportance importance,
        @NotNull ChecklistItemType itemType,
        // MULTIPLE_CHOICE 타입 문항의 선택지("가스보일러,기름보일러,전기보일러,지역난방" 형식). 그 외 타입은 사용하지 않는다.
        @Size(max = 255) String options,
        ChecklistItemCode code,
        @NotNull @Min(1) Integer displayOrder,
        @Size(max = 255) String applicablePropertyTypes
) {
}
