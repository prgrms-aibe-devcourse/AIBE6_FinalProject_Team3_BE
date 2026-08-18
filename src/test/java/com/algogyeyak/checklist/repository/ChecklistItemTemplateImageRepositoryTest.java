package com.algogyeyak.checklist.repository;

import com.algogyeyak.checklist.entity.ChecklistCategory;
import com.algogyeyak.checklist.entity.ChecklistImportance;
import com.algogyeyak.checklist.entity.ChecklistItemTemplate;
import com.algogyeyak.checklist.entity.ChecklistItemTemplateImage;
import com.algogyeyak.checklist.entity.ChecklistItemType;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class ChecklistItemTemplateImageRepositoryTest {

    @Autowired
    private ChecklistItemTemplateImageRepository imageRepository;

    @Autowired
    private ChecklistItemTemplateRepository templateRepository;

    private ChecklistItemTemplate saveTemplate() {
        return templateRepository.save(ChecklistItemTemplate.builder()
                .version(1)
                .category(ChecklistCategory.INDOOR)
                .content("누수 확인")
                .importance(ChecklistImportance.GENERAL)
                .itemType(ChecklistItemType.CHECK)
                .displayOrder(1)
                .active(true)
                .build());
    }

    @Test
    void 템플릿의_이미지를_표시순서대로_반환한다() {
        ChecklistItemTemplate template = saveTemplate();
        imageRepository.save(ChecklistItemTemplateImage.builder()
                .template(template).imageUrl("https://example.com/2.jpg").displayOrder(2).build());
        imageRepository.save(ChecklistItemTemplateImage.builder()
                .template(template).imageUrl("https://example.com/1.jpg").displayOrder(1).build());

        List<ChecklistItemTemplateImage> result =
                imageRepository.findByTemplateIdOrderByDisplayOrderAsc(template.getId());

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getImageUrl()).isEqualTo("https://example.com/1.jpg");
        assertThat(result.get(1).getImageUrl()).isEqualTo("https://example.com/2.jpg");
    }

    @Test
    void 다른_템플릿의_이미지는_포함하지_않는다() {
        ChecklistItemTemplate template = saveTemplate();
        ChecklistItemTemplate other = saveTemplate();
        imageRepository.save(ChecklistItemTemplateImage.builder()
                .template(template).imageUrl("https://example.com/mine.jpg").displayOrder(1).build());
        imageRepository.save(ChecklistItemTemplateImage.builder()
                .template(other).imageUrl("https://example.com/other.jpg").displayOrder(1).build());

        List<ChecklistItemTemplateImage> result =
                imageRepository.findByTemplateIdOrderByDisplayOrderAsc(template.getId());

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getImageUrl()).isEqualTo("https://example.com/mine.jpg");
    }
}
