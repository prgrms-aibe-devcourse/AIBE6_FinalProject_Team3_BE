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

    @Test
    @DisplayName("적용 대상 매물유형 토큰 사이에 공백이 있어도(관리자 페이지 자유 입력) 정상 매칭된다")
    void isApplicableToTrimsWhitespaceAroundTokens() {
        ChecklistItemTemplate template = baseBuilder()
                .applicablePropertyTypes("OFFICETEL, MULTI_FAMILY")
                .build();

        assertThat(template.isApplicableTo(PropertyType.OFFICETEL)).isTrue();
        assertThat(template.isApplicableTo(PropertyType.MULTI_FAMILY)).isTrue();
        assertThat(template.isApplicableTo(PropertyType.DETACHED_HOUSE)).isFalse();
    }

    @Test
    @DisplayName("update()는 version을 제외한 모든 필드를 바꾼다")
    void updateChangesAllFieldsExceptVersion() {
        ChecklistItemTemplate template = baseBuilder().build();

        template.update(
                ChecklistCategory.DOCUMENTS,
                "등기부등본을 확인했나요?",
                "안내 문구",
                "쉬운 설명",
                ChecklistImportance.REQUIRED,
                ChecklistItemType.YES_NO,
                null,
                ChecklistItemCode.TRUST_REGISTRATION,
                5,
                "OFFICETEL",
                false
        );

        assertThat(template.getVersion()).isEqualTo(2); // baseBuilder()의 version 그대로 유지
        assertThat(template.getCategory()).isEqualTo(ChecklistCategory.DOCUMENTS);
        assertThat(template.getContent()).isEqualTo("등기부등본을 확인했나요?");
        assertThat(template.getGuideText()).isEqualTo("안내 문구");
        assertThat(template.getHelperText()).isEqualTo("쉬운 설명");
        assertThat(template.getImportance()).isEqualTo(ChecklistImportance.REQUIRED);
        assertThat(template.getItemType()).isEqualTo(ChecklistItemType.YES_NO);
        assertThat(template.getCode()).isEqualTo(ChecklistItemCode.TRUST_REGISTRATION);
        assertThat(template.getDisplayOrder()).isEqualTo(5);
        assertThat(template.getApplicablePropertyTypes()).isEqualTo("OFFICETEL");
        assertThat(template.isActive()).isFalse();
    }

    @Test
    @DisplayName("update()는 options도 함께 바꾼다")
    void updateChangesOptions() {
        ChecklistItemTemplate template = baseBuilder().build();

        template.update(
                ChecklistCategory.INDOOR,
                "보일러 종류가 무엇인가요?",
                null,
                null,
                ChecklistImportance.GENERAL,
                ChecklistItemType.MULTIPLE_CHOICE,
                "가스보일러,기름보일러,전기보일러,지역난방",
                null,
                1,
                null,
                true
        );

        assertThat(template.getOptions()).isEqualTo("가스보일러,기름보일러,전기보일러,지역난방");
    }
}
