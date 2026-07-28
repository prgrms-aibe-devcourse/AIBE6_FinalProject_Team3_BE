package com.algogyeyak.checklist.entity;

import com.algogyeyak.property.entity.PropertyType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ChecklistItemTemplate")
class ChecklistItemTemplateTest {

    private ChecklistItemTemplate.ChecklistItemTemplateBuilder baseBuilder() {
        return ChecklistItemTemplate.builder()
                .version(2)
                .category(ChecklistCategory.SAFETY)
                .content("현관 잠금장치가 정상 작동하나요?")
                .importance(ChecklistImportance.GENERAL)
                .itemType(ChecklistItemType.CHECK)
                .displayOrder(1)
                .active(true);
    }

    @Test
    @DisplayName("적용 대상 매물유형을 지정하지 않으면 모든 매물유형에 적용된다")
    void isApplicableToReturnsTrueForAllTypesWhenNotRestricted() {
        ChecklistItemTemplate template = baseBuilder().build();

        assertThat(template.isApplicableTo(PropertyType.OFFICETEL)).isTrue();
        assertThat(template.isApplicableTo(PropertyType.MULTI_FAMILY)).isTrue();
        assertThat(template.isApplicableTo(PropertyType.DETACHED_HOUSE)).isTrue();
    }

    @Test
    @DisplayName("적용 대상 매물유형을 지정하면 해당 유형에서만 적용된다")
    void isApplicableToReturnsTrueOnlyForListedTypes() {
        ChecklistItemTemplate template = baseBuilder()
                .applicablePropertyTypes("OFFICETEL,MULTI_FAMILY")
                .build();

        assertThat(template.isApplicableTo(PropertyType.OFFICETEL)).isTrue();
        assertThat(template.isApplicableTo(PropertyType.MULTI_FAMILY)).isTrue();
        assertThat(template.isApplicableTo(PropertyType.DETACHED_HOUSE)).isFalse();
    }
}
