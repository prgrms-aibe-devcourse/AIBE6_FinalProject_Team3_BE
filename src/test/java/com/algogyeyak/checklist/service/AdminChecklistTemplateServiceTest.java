package com.algogyeyak.checklist.service;

import com.algogyeyak.checklist.dto.AdminChecklistItemTemplateCreateRequest;
import com.algogyeyak.checklist.dto.AdminChecklistItemTemplateResponse;
import com.algogyeyak.checklist.dto.AdminChecklistItemTemplateUpdateRequest;
import com.algogyeyak.checklist.entity.ChecklistCategory;
import com.algogyeyak.checklist.entity.ChecklistImportance;
import com.algogyeyak.checklist.entity.ChecklistItemTemplate;
import com.algogyeyak.checklist.entity.ChecklistItemType;
import com.algogyeyak.checklist.repository.ChecklistItemTemplateRepository;
import com.algogyeyak.global.error.ErrorCode;
import com.algogyeyak.global.exception.BusinessException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("AdminChecklistTemplateService")
class AdminChecklistTemplateServiceTest {

    private final ChecklistItemTemplateRepository checklistItemTemplateRepository = mock(ChecklistItemTemplateRepository.class);
    private final AdminChecklistTemplateService adminChecklistTemplateService =
            new AdminChecklistTemplateService(checklistItemTemplateRepository);

    private ChecklistItemTemplate template(Long id, int version, int displayOrder) {
        ChecklistItemTemplate template = ChecklistItemTemplate.builder()
                .version(version)
                .category(ChecklistCategory.INDOOR)
                .content("누수 확인")
                .importance(ChecklistImportance.GENERAL)
                .itemType(ChecklistItemType.CHECK)
                .displayOrder(displayOrder)
                .active(true)
                .build();
        ReflectionTestUtils.setField(template, "id", id);
        return template;
    }

    @Test
    @DisplayName("전체 문항을 displayOrder 순으로 반환한다")
    void listReturnsAllTemplatesOrderedByDisplayOrder() {
        when(checklistItemTemplateRepository.findAllByOrderByDisplayOrderAsc())
                .thenReturn(List.of(template(1L, 2, 1), template(2L, 2, 2)));

        List<AdminChecklistItemTemplateResponse> result = adminChecklistTemplateService.list();

        assertThat(result).hasSize(2);
        assertThat(result.get(0).id()).isEqualTo(1L);
    }

    @Test
    @DisplayName("생성 시 기존 문항 중 가장 높은 버전을 그대로 물려받는다")
    void createInheritsHighestExistingVersion() {
        when(checklistItemTemplateRepository.findAllByOrderByDisplayOrderAsc())
                .thenReturn(List.of(template(1L, 2, 1), template(2L, 3, 2)));
        when(checklistItemTemplateRepository.save(any(ChecklistItemTemplate.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        AdminChecklistItemTemplateResponse result = adminChecklistTemplateService.create(
                new AdminChecklistItemTemplateCreateRequest(
                        ChecklistCategory.AREA, "주차 공간이 충분한가요?", null, null,
                        ChecklistImportance.GENERAL, ChecklistItemType.CHECK, null, 30, null
                )
        );

        assertThat(result.version()).isEqualTo(3);
        assertThat(result.active()).isTrue();
        assertThat(result.content()).isEqualTo("주차 공간이 충분한가요?");
    }

    @Test
    @DisplayName("기존 문항이 하나도 없으면 버전 1로 생성한다")
    void createDefaultsToVersionOneWhenNoTemplatesExist() {
        when(checklistItemTemplateRepository.findAllByOrderByDisplayOrderAsc()).thenReturn(List.of());
        when(checklistItemTemplateRepository.save(any(ChecklistItemTemplate.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        AdminChecklistItemTemplateResponse result = adminChecklistTemplateService.create(
                new AdminChecklistItemTemplateCreateRequest(
                        ChecklistCategory.AREA, "주차 공간이 충분한가요?", null, null,
                        ChecklistImportance.GENERAL, ChecklistItemType.CHECK, null, 1, null
                )
        );

        assertThat(result.version()).isEqualTo(1);
    }

    @Test
    @DisplayName("존재하는 문항을 수정하면 변경된 필드가 반영된다")
    void updateChangesExistingTemplateFields() {
        ChecklistItemTemplate existing = template(1L, 2, 1);
        when(checklistItemTemplateRepository.findById(1L)).thenReturn(Optional.of(existing));

        AdminChecklistItemTemplateResponse result = adminChecklistTemplateService.update(
                1L,
                new AdminChecklistItemTemplateUpdateRequest(
                        ChecklistCategory.SAFETY, "창문 잠금장치가 정상 작동하나요?", "안내", null,
                        ChecklistImportance.REQUIRED, ChecklistItemType.CHECK, null, 9, null, false
                )
        );

        assertThat(result.category()).isEqualTo(ChecklistCategory.SAFETY);
        assertThat(result.content()).isEqualTo("창문 잠금장치가 정상 작동하나요?");
        assertThat(result.importance()).isEqualTo(ChecklistImportance.REQUIRED);
        assertThat(result.displayOrder()).isEqualTo(9);
        assertThat(result.active()).isFalse();
        assertThat(result.version()).isEqualTo(2); // 수정으로 버전이 바뀌지 않는다
    }

    @Test
    @DisplayName("존재하지 않는 문항을 수정하면 ADMIN_CHECKLIST_TEMPLATE_NOT_FOUND 예외가 발생한다")
    void updateThrowsWhenTemplateNotFound() {
        when(checklistItemTemplateRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> adminChecklistTemplateService.update(
                999L,
                new AdminChecklistItemTemplateUpdateRequest(
                        ChecklistCategory.AREA, "내용", null, null,
                        ChecklistImportance.GENERAL, ChecklistItemType.CHECK, null, 1, null, true
                )
        ))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> assertThat(((BusinessException) exception).getErrorCode())
                        .isEqualTo(ErrorCode.ADMIN_CHECKLIST_TEMPLATE_NOT_FOUND));
    }

    @Test
    @DisplayName("존재하는 문항을 삭제하면 repository.delete가 호출된다")
    void deleteRemovesExistingTemplate() {
        ChecklistItemTemplate existing = template(1L, 2, 1);
        when(checklistItemTemplateRepository.findById(1L)).thenReturn(Optional.of(existing));

        adminChecklistTemplateService.delete(1L);

        verify(checklistItemTemplateRepository).delete(existing);
    }

    @Test
    @DisplayName("존재하지 않는 문항을 삭제하면 ADMIN_CHECKLIST_TEMPLATE_NOT_FOUND 예외가 발생한다")
    void deleteThrowsWhenTemplateNotFound() {
        when(checklistItemTemplateRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> adminChecklistTemplateService.delete(999L))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> assertThat(((BusinessException) exception).getErrorCode())
                        .isEqualTo(ErrorCode.ADMIN_CHECKLIST_TEMPLATE_NOT_FOUND));
    }
}
