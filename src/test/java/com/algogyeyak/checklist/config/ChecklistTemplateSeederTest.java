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
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("ChecklistTemplateSeeder")
class ChecklistTemplateSeederTest {

    private static final String LEAK_CONTENT = "벽면·천장·바닥에 누수 흔적이나 곰팡이가 없나요?";
    private static final String LIGHT_CONTENT = "채광은 충분한가요?";
    // TEMPLATE_IMAGE_KEYS_BY_CONTENT에 등록된 나머지 3개 문항 - 아래 helper에서 "이미 이미지가 붙어있는
    // 기존 문항"으로 미리 존재시켜, desired 전체(25개)를 순회하는 실제 seeder 로직이 테스트 대상이
    // 아닌 이 문항들까지 신규 삽입으로 착각해 의도치 않은 이미지 저장을 유발하지 않도록 막는 데 쓴다.
    private static final String OUTLET_CONTENT = "콘센트·전기 배선은 정상 작동하나요?";
    private static final String BREAKER_CONTENT = "차단기함 상태를 확인했나요?";
    private static final String EXTINGUISHER_CONTENT = "소화기·화재감지기가 비치되어 있나요?";

    private final ChecklistItemTemplateRepository repository = mock(ChecklistItemTemplateRepository.class);
    private final ChecklistItemTemplateImageRepository imageRepository = mock(ChecklistItemTemplateImageRepository.class);
    private final S3PresignService s3PresignService = mock(S3PresignService.class);
    private final ChecklistTemplateSeeder seeder =
            new ChecklistTemplateSeeder(repository, imageRepository, s3PresignService);

    private final int desiredCount = ChecklistTemplateSeedData.initialTemplates().size();

    // save()가 인자로 받은 엔티티를 그대로 반환하도록(=DB가 그대로 저장/갱신에 성공한 것처럼) 기본
    // 동작을 맞춰준다 - 실제로는 각 테스트가 필요에 따라 특정 인자에 대해서만 다시 stub한다.
    private void stubSaveToReturnArgument() {
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private ChecklistItemTemplate mockExisting(String content, List<ChecklistItemTemplateImage> images) {
        ChecklistItemTemplate existing = mock(ChecklistItemTemplate.class);
        when(existing.getContent()).thenReturn(content);
        when(existing.getImages()).thenReturn(images);
        return existing;
    }

    private List<ChecklistItemTemplate> otherImagedTemplatesAlreadyHaveImages(String excludedContent) {
        return Stream.of(LEAK_CONTENT, OUTLET_CONTENT, BREAKER_CONTENT, EXTINGUISHER_CONTENT)
                .filter(content -> !content.equals(excludedContent))
                .map(content -> mockExisting(content, List.of(mock(ChecklistItemTemplateImage.class))))
                .collect(Collectors.toList());
    }

    @Test
    @DisplayName("템플릿이 비어있으면 초기 시드 데이터를 문항별로 개별 저장한다")
    void seedsInitialTemplatesWhenRepositoryIsEmpty() throws Exception {
        when(repository.findAllWithImages()).thenReturn(List.of());
        stubSaveToReturnArgument();

        seeder.run(new DefaultApplicationArguments());

        verify(repository, times(desiredCount)).save(any());
    }

    @Test
    @DisplayName("일부 문항만 이미 있으면 없는 문항만 추가로 저장한다")
    void insertsOnlyMissingTemplatesWhenSomeAlreadyExist() throws Exception {
        ChecklistItemTemplate existing = mockExisting(LIGHT_CONTENT, List.of());
        when(repository.findAllWithImages()).thenReturn(List.of(existing));
        stubSaveToReturnArgument();

        seeder.run(new DefaultApplicationArguments());

        // desired 문항 하나하나가 저장(신규 삽입 또는 기존 행 갱신) 대상이 되므로, 저장 시도 총
        // 횟수는 항상 desiredCount와 같다 - 그중 하나(existing)만 매칭되어 재동기화되고 나머지는
        // 새로 삽입된다.
        verify(repository, times(desiredCount)).save(any());
        verify(repository).save(existing);
        verify(repository, times(desiredCount - 1)).save(argThat(
                template -> template != existing && !template.getContent().equals(LIGHT_CONTENT)));
    }

    @Test
    @DisplayName("이미 있는 문항은 v3 시드 설계(순번 등)에 맞춰 재동기화하고, seedKey도 채워 유니크 제약의 보호를 받게 한다")
    void resyncsExistingTemplateToLatestSeedAndMarksAsSeeded() throws Exception {
        ChecklistItemTemplate existing = mockExisting(LIGHT_CONTENT, List.of());
        when(repository.findAllWithImages()).thenReturn(List.of(existing));
        stubSaveToReturnArgument();

        seeder.run(new DefaultApplicationArguments());

        verify(existing).resyncFromSeed(
                eq(ChecklistCategory.INDOOR), eq(LIGHT_CONTENT), any(), any(),
                eq(ChecklistImportance.GENERAL), eq(ChecklistItemType.CHECK), any(), any(),
                eq(2), any(), eq(3)
        );
        // resyncFromSeed()로 문구/순번을 맞춘 뒤 markAsSeeded()로 seedKey를 채우고, 그 상태를
        // 명시적으로 save()해야 실제 반영된다(더 이상 메서드 전체를 감싸는 트랜잭션의 커밋 시점
        // dirty-checking에 기대지 않는다).
        verify(existing).markAsSeeded();
        verify(repository).save(existing);
    }

    @Test
    @DisplayName("기존 문항 중 예시 이미지가 아직 없는 문항에는 이미지를 뒤늦게 연결한다")
    void attachesImagesToExistingTemplateThatNeverGotThem() throws Exception {
        ChecklistItemTemplate existing = mockExisting(LEAK_CONTENT, List.of());
        List<ChecklistItemTemplate> allExisting = new ArrayList<>(otherImagedTemplatesAlreadyHaveImages(LEAK_CONTENT));
        allExisting.add(existing);
        when(repository.findAllWithImages()).thenReturn(allExisting);
        stubSaveToReturnArgument();
        when(s3PresignService.generateDownloadUrl(any(), eq(S3ImagePurpose.CHECKLIST_TEMPLATE)))
                .thenReturn("https://bucket.s3.ap-northeast-2.amazonaws.com/dummy.jpg");

        seeder.run(new DefaultApplicationArguments());

        verify(imageRepository, times(3)).save(any());
    }

    @Test
    @DisplayName("기존 문항에 이미 예시 이미지가 붙어있으면 다시 연결하지 않는다")
    void skipsImageAttachmentWhenExistingTemplateAlreadyHasImages() throws Exception {
        ChecklistItemTemplate existing = mockExisting(LEAK_CONTENT, List.of(mock(ChecklistItemTemplateImage.class)));
        List<ChecklistItemTemplate> allExisting = new ArrayList<>(otherImagedTemplatesAlreadyHaveImages(LEAK_CONTENT));
        allExisting.add(existing);
        when(repository.findAllWithImages()).thenReturn(allExisting);
        stubSaveToReturnArgument();

        seeder.run(new DefaultApplicationArguments());

        verify(imageRepository, never()).save(any());
    }

    @Test
    @DisplayName("시드 저장 직후 누수 문항에 예시 이미지 3장을 CHECKLIST_TEMPLATE purpose로 조회한 URL로 연결한다")
    void attachesLeakImagesToLeakTemplate() throws Exception {
        // otherImagedTemplatesAlreadyHaveImages()가 내부적으로 mock()/when()을 호출하므로, when(...)의
        // 인자 자리에서 바로 부르면 그 중첩 스터빙이 바깥 when(repository.findAllWithImages())의 미완료
        // 스터빙과 뒤섞여 Mockito가 UnfinishedStubbingException을 던진다 - 지역 변수로 먼저 평가한다.
        List<ChecklistItemTemplate> allExisting = otherImagedTemplatesAlreadyHaveImages(LEAK_CONTENT);
        when(repository.findAllWithImages()).thenReturn(allExisting);
        stubSaveToReturnArgument();
        when(s3PresignService.generateDownloadUrl(eq("checklist-template-images/1.jpg"), eq(S3ImagePurpose.CHECKLIST_TEMPLATE)))
                .thenReturn("https://bucket.s3.ap-northeast-2.amazonaws.com/checklist-template-images/1.jpg");
        when(s3PresignService.generateDownloadUrl(eq("checklist-template-images/2.jpg"), eq(S3ImagePurpose.CHECKLIST_TEMPLATE)))
                .thenReturn("https://bucket.s3.ap-northeast-2.amazonaws.com/checklist-template-images/2.jpg");
        when(s3PresignService.generateDownloadUrl(eq("checklist-template-images/3.jpg"), eq(S3ImagePurpose.CHECKLIST_TEMPLATE)))
                .thenReturn("https://bucket.s3.ap-northeast-2.amazonaws.com/checklist-template-images/3.jpg");

        seeder.run(new DefaultApplicationArguments());

        ChecklistItemTemplate leakTemplate = ChecklistTemplateSeedData.initialTemplates().stream()
                .filter(t -> t.getContent().equals(LEAK_CONTENT))
                .findFirst()
                .orElseThrow();

        org.mockito.ArgumentCaptor<ChecklistItemTemplateImage> captor =
                org.mockito.ArgumentCaptor.forClass(ChecklistItemTemplateImage.class);
        verify(imageRepository, times(3)).save(captor.capture());
        List<ChecklistItemTemplateImage> saved = captor.getAllValues();
        assertThat(saved).hasSize(3);
        assertThat(saved).allSatisfy(image -> assertThat(image.getTemplate().getContent()).isEqualTo(LEAK_CONTENT));
        assertThat(saved.get(0).getImageUrl()).isEqualTo("https://bucket.s3.ap-northeast-2.amazonaws.com/checklist-template-images/1.jpg");
        assertThat(saved.get(0).getDisplayOrder()).isEqualTo(1);
        assertThat(saved.get(2).getDisplayOrder()).isEqualTo(3);
        assertThat(leakTemplate).isNotNull();
    }

    @Test
    @DisplayName("동시 기동 중인 다른 인스턴스가 이미 심은 시드 문항은 seedKey 유니크 제약 위반을 잡아 조용히 건너뛰고, 앱 기동 자체는 실패하지 않는다")
    void skipsTemplateThatAnotherInstanceAlreadySeededConcurrently() throws Exception {
        when(repository.findAllWithImages()).thenReturn(List.of());
        // 이 인스턴스가 읽었을 땐 없었지만(findAllWithImages()가 빈 목록), 실제로 저장을 시도하는
        // 순간에는 이미 다른 인스턴스가 커밋을 마친 상황을 흉내낸다 - 하나의 특정 문항(LIGHT_CONTENT)만
        // 유니크 제약 위반으로 실패하고, 나머지는 정상적으로 저장된다.
        // argThat 매처는 Mockito가 스텁 매칭/검증 과정에서 null 인자로도 평가할 수 있어 null-safe해야
        // 한다(그렇지 않으면 getContent() 호출 시 NPE).
        when(repository.save(argThat(template -> template != null && template.getContent().equals(LIGHT_CONTENT))))
                .thenThrow(new DataIntegrityViolationException("Duplicate entry for key 'seed_key'"));
        when(repository.save(argThat(template -> template != null && !template.getContent().equals(LIGHT_CONTENT))))
                .thenAnswer(invocation -> invocation.getArgument(0));

        assertThatCode(() -> seeder.run(new DefaultApplicationArguments())).doesNotThrowAnyException();

        // 실패한 문항(LIGHT_CONTENT)이 있어도 나머지 desired 문항들에 대한 저장 시도는 계속
        // 이뤄진다 - 즉 desired 전체 문항 수만큼 save()가 호출된다(하나는 예외를 던졌을 뿐).
        verify(repository, times(desiredCount)).save(any());
        // 실패한 문항(LIGHT_CONTENT)은 이미지가 없는 문항이라 애초에 이미지 연결 대상이 아니었으므로,
        // 유니크 제약 위반으로 건너뛴 것과 무관하게 그 문항에 대한 이미지 저장 시도는 없다.
        verify(imageRepository, never()).save(argThat(
                image -> image.getTemplate() != null && image.getTemplate().getContent().equals(LIGHT_CONTENT)));
    }

    // 회귀 테스트 - 템플릿 저장(seedKey)뿐 아니라 이미지 저장도 같은 종류의 레이스를 겪는다: 두
    // 인스턴스가 동시에 "이 문항엔 아직 이미지가 없다"고 읽으면 둘 다 같은 이미지 세트를 붙이려
    // 시도할 수 있다. ChecklistItemTemplateImage의 (template_id, display_order) 유니크 제약이
    // 그 가드다 - 한 이미지(예: 2번째)만 다른 인스턴스가 먼저 커밋해 유니크 제약 위반이 나도, 그
    // 예외 하나 때문에 앱 기동 전체가 실패하거나 같은 문항의 나머지 이미지 삽입까지 중단돼서는
    // 안 된다.
    @Test
    @DisplayName("동시 기동 중인 다른 인스턴스가 이미 붙인 이미지는 유니크 제약 위반을 잡아 조용히 건너뛰고, 같은 문항의 나머지 이미지는 계속 저장한다")
    void skipsImageThatAnotherInstanceAlreadyAttachedConcurrently() throws Exception {
        List<ChecklistItemTemplate> allExisting = otherImagedTemplatesAlreadyHaveImages(LEAK_CONTENT);
        when(repository.findAllWithImages()).thenReturn(allExisting);
        stubSaveToReturnArgument();
        when(s3PresignService.generateDownloadUrl(any(), eq(S3ImagePurpose.CHECKLIST_TEMPLATE)))
                .thenReturn("https://bucket.s3.ap-northeast-2.amazonaws.com/dummy.jpg");
        // displayOrder=2로 저장을 시도하는 이미지만 다른 인스턴스가 이미 심어둔 상황을 흉내낸다.
        when(imageRepository.save(argThat(image -> image != null && image.getDisplayOrder() == 2)))
                .thenThrow(new DataIntegrityViolationException(
                        "Duplicate entry for key 'uk_checklist_item_template_image_template_display_order'"));

        assertThatCode(() -> seeder.run(new DefaultApplicationArguments())).doesNotThrowAnyException();

        // LEAK_CONTENT엔 이미지가 3장(순서 1,2,3) 있다 - 2번만 실패해도 1번/3번은 그대로 저장 시도된다.
        verify(imageRepository).save(argThat(image -> image != null && image.getDisplayOrder() == 1));
        verify(imageRepository).save(argThat(image -> image != null && image.getDisplayOrder() == 3));
    }
}
