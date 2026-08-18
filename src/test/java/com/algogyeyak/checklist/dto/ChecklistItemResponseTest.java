package com.algogyeyak.checklist.dto;

import com.algogyeyak.checklist.entity.ChecklistCategory;
import com.algogyeyak.checklist.entity.ChecklistImportance;
import com.algogyeyak.checklist.entity.ChecklistItem;
import com.algogyeyak.checklist.entity.ChecklistItemTemplate;
import com.algogyeyak.checklist.entity.ChecklistItemTemplateImage;
import com.algogyeyak.checklist.entity.ChecklistItemType;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ChecklistItemResponse")
class ChecklistItemResponseTest {

    private ChecklistItemTemplate template() {
        return ChecklistItemTemplate.builder()
                .version(1)
                .category(ChecklistCategory.INDOOR)
                .content("누수 확인")
                .importance(ChecklistImportance.GENERAL)
                .itemType(ChecklistItemType.CHECK)
                .displayOrder(1)
                .active(true)
                .build();
    }

    private ChecklistItem itemFrom(ChecklistItemTemplate template) {
        return ChecklistItem.builder()
                .template(template)
                .category(template.getCategory())
                .content(template.getContent())
                .importance(template.getImportance())
                .itemType(template.getItemType())
                .displayOrder(template.getDisplayOrder())
                .build();
    }

    @Test
    @DisplayName("템플릿에 연결된 예시 이미지를 표시순서대로 담는다")
    void fromIncludesTemplateImagesInDisplayOrder() {
        ChecklistItemTemplate template = template();
        ChecklistItemTemplateImage second = ChecklistItemTemplateImage.builder()
                .template(template).imageUrl("https://example.com/2.jpg").displayOrder(2).build();
        ChecklistItemTemplateImage first = ChecklistItemTemplateImage.builder()
                .template(template).imageUrl("https://example.com/1.jpg").displayOrder(1).build();
        ReflectionTestUtils.setField(template, "images", List.of(first, second));

        ChecklistItemResponse response = ChecklistItemResponse.from(itemFrom(template));

        assertThat(response.images()).containsExactly("https://example.com/1.jpg", "https://example.com/2.jpg");
    }

    @Test
    @DisplayName("원본 템플릿이 없는(과거 데이터) 항목은 빈 이미지 목록을 반환한다")
    void fromReturnsEmptyImagesWhenTemplateIsNull() {
        ChecklistItem item = ChecklistItem.builder()
                .category(ChecklistCategory.INDOOR)
                .content("누수 확인")
                .importance(ChecklistImportance.GENERAL)
                .itemType(ChecklistItemType.CHECK)
                .displayOrder(1)
                .build();

        ChecklistItemResponse response = ChecklistItemResponse.from(item);

        assertThat(response.images()).isEmpty();
    }
}
