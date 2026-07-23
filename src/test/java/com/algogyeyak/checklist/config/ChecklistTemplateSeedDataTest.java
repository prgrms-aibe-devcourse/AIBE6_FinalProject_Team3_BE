package com.algogyeyak.checklist.config;

import com.algogyeyak.checklist.entity.ChecklistCategory;
import com.algogyeyak.checklist.entity.ChecklistItemCode;
import com.algogyeyak.checklist.entity.ChecklistItemTemplate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ChecklistTemplateSeedData")
class ChecklistTemplateSeedDataTest {

    private final List<ChecklistItemTemplate> templates = ChecklistTemplateSeedData.initialTemplates();

    @Test
    @DisplayName("전체 문항 수는 20~24개다")
    void hasBetween20And24Items() {
        assertThat(templates.size()).isBetween(20, 24);
    }

    @Test
    @DisplayName("5개 카테고리(실내/소음/보안/서류/주변)를 모두 포함한다")
    void coversAllFiveCategories() {
        Map<ChecklistCategory, Long> countByCategory = templates.stream()
                .collect(Collectors.groupingBy(ChecklistItemTemplate::getCategory, Collectors.counting()));

        assertThat(countByCategory).containsOnlyKeys(
                ChecklistCategory.INDOOR, ChecklistCategory.NOISE, ChecklistCategory.SAFETY,
                ChecklistCategory.DOCUMENTS, ChecklistCategory.AREA
        );
    }

    @Test
    @DisplayName("자동 issueFound 규칙이 붙는 6개 code가 각각 정확히 1개씩 존재한다")
    void containsEachRuleCodeExactlyOnce() {
        List<ChecklistItemCode> expectedCodes = List.of(
                ChecklistItemCode.TRUST_REGISTRATION,
                ChecklistItemCode.OWNERSHIP_MATCH,
                ChecklistItemCode.OWNERSHIP_ACQUISITION_DATE,
                ChecklistItemCode.TAX_DELINQUENCY_NOTICE,
                ChecklistItemCode.DATE_OF_CONFIRMATION_REQUEST,
                ChecklistItemCode.RESIDENT_REGISTRATION_REQUEST
        );

        for (ChecklistItemCode code : expectedCodes) {
            long count = templates.stream().filter(t -> t.getCode() == code).count();
            assertThat(count).as("code=%s", code).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("모든 문항은 버전 1, active=true로 생성된다")
    void allItemsAreVersionOneAndActive() {
        assertThat(templates).allSatisfy(template -> {
            assertThat(template.getVersion()).isEqualTo(1);
            assertThat(template.isActive()).isTrue();
        });
    }
}
