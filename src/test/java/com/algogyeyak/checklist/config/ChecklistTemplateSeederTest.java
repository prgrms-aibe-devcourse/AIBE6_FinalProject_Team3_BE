package com.algogyeyak.checklist.config;

import com.algogyeyak.checklist.entity.ChecklistCategory;
import com.algogyeyak.checklist.entity.ChecklistImportance;
import com.algogyeyak.checklist.entity.ChecklistItemTemplate;
import com.algogyeyak.checklist.entity.ChecklistItemTemplateImage;
import com.algogyeyak.checklist.entity.ChecklistItemType;
import com.algogyeyak.checklist.repository.ChecklistItemTemplateImageRepository;
import com.algogyeyak.checklist.repository.ChecklistItemTemplateRepository;
import com.algogyeyak.global.s3.service.S3PresignService;
import com.algogyeyak.global.s3.util.S3ImagePurpose;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("ChecklistTemplateSeeder")
class ChecklistTemplateSeederTest {

    private final ChecklistItemTemplateRepository repository = mock(ChecklistItemTemplateRepository.class);
    private final ChecklistItemTemplateImageRepository imageRepository = mock(ChecklistItemTemplateImageRepository.class);
    private final S3PresignService s3PresignService = mock(S3PresignService.class);
    private final ChecklistTemplateSeeder seeder =
            new ChecklistTemplateSeeder(repository, imageRepository, s3PresignService);

    private ChecklistItemTemplate templateWithId(Long id, String content) {
        ChecklistItemTemplate template = ChecklistItemTemplate.builder()
                .version(3)
                .category(ChecklistCategory.INDOOR)
                .content(content)
                .importance(ChecklistImportance.GENERAL)
                .itemType(ChecklistItemType.CHECK)
                .displayOrder(1)
                .active(true)
                .build();
        ReflectionTestUtils.setField(template, "id", id);
        return template;
    }

    @Test
    @DisplayName("템플릿이 비어있으면 초기 시드 데이터를 저장한다")
    void seedsInitialTemplatesWhenRepositoryIsEmpty() throws Exception {
        when(repository.count()).thenReturn(0L);
        when(repository.saveAll(anyList())).thenReturn(List.of());

        seeder.run(new DefaultApplicationArguments());

        verify(repository).saveAll(anyList());
    }

    @Test
    @DisplayName("이미 템플릿이 있으면 다시 저장하지 않는다")
    void doesNotReseedWhenTemplatesAlreadyExist() throws Exception {
        when(repository.count()).thenReturn(23L);

        seeder.run(new DefaultApplicationArguments());

        verify(repository, never()).saveAll(anyList());
        verify(imageRepository, never()).save(any());
    }

    @Test
    @DisplayName("시드 저장 직후 누수 문항에 예시 이미지 3장을 CHECKLIST_TEMPLATE purpose로 조회한 URL로 연결한다")
    void attachesLeakImagesToLeakTemplate() throws Exception {
        ChecklistItemTemplate leakTemplate = templateWithId(1L, "벽면·천장·바닥에 누수 흔적이나 곰팡이가 없나요?");
        when(repository.count()).thenReturn(0L);
        when(repository.saveAll(anyList())).thenReturn(List.of(leakTemplate));
        when(s3PresignService.generateDownloadUrl(eq("checklist-template-images/1.jpg"), eq(S3ImagePurpose.CHECKLIST_TEMPLATE)))
                .thenReturn("https://bucket.s3.ap-northeast-2.amazonaws.com/checklist-template-images/1.jpg");
        when(s3PresignService.generateDownloadUrl(eq("checklist-template-images/2.jpg"), eq(S3ImagePurpose.CHECKLIST_TEMPLATE)))
                .thenReturn("https://bucket.s3.ap-northeast-2.amazonaws.com/checklist-template-images/2.jpg");
        when(s3PresignService.generateDownloadUrl(eq("checklist-template-images/3.jpg"), eq(S3ImagePurpose.CHECKLIST_TEMPLATE)))
                .thenReturn("https://bucket.s3.ap-northeast-2.amazonaws.com/checklist-template-images/3.jpg");

        seeder.run(new DefaultApplicationArguments());

        org.mockito.ArgumentCaptor<ChecklistItemTemplateImage> captor =
                org.mockito.ArgumentCaptor.forClass(ChecklistItemTemplateImage.class);
        verify(imageRepository, times(3)).save(captor.capture());
        List<ChecklistItemTemplateImage> saved = captor.getAllValues();
        assertThat(saved).hasSize(3);
        assertThat(saved).allSatisfy(image -> assertThat(image.getTemplate()).isEqualTo(leakTemplate));
        assertThat(saved.get(0).getImageUrl()).isEqualTo("https://bucket.s3.ap-northeast-2.amazonaws.com/checklist-template-images/1.jpg");
        assertThat(saved.get(0).getDisplayOrder()).isEqualTo(1);
        assertThat(saved.get(2).getDisplayOrder()).isEqualTo(3);
    }
}
