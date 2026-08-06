package com.algogyeyak.checklist.service;

import com.algogyeyak.checklist.dto.AdminChecklistItemTemplateCreateRequest;
import com.algogyeyak.checklist.dto.AdminChecklistItemTemplateResponse;
import com.algogyeyak.checklist.dto.AdminChecklistItemTemplateUpdateRequest;
import com.algogyeyak.checklist.entity.ChecklistCategory;
import com.algogyeyak.checklist.entity.ChecklistImportance;
import com.algogyeyak.checklist.entity.ChecklistItemCode;
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

    private ChecklistItemTemplate templateWithCode(Long id, ChecklistItemCode code, ChecklistItemType itemType) {
        ChecklistItemTemplate template = ChecklistItemTemplate.builder()
                .version(2)
                .category(ChecklistCategory.DOCUMENTS)
                .content("특수 문항")
                .importance(ChecklistImportance.REQUIRED)
                .itemType(itemType)
                .code(code)
                .displayOrder(1)
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
    @DisplayName("존재하지 않는 매물유형으로 생성하면 ADMIN_CHECKLIST_TEMPLATE_INVALID_PROPERTY_TYPE 예외가 발생한다")
    void createThrowsWhenApplicablePropertyTypeIsUnknown() {
        // 관리자 화면은 체크박스라 정상 값만 보내지만, Swagger/직접 API 호출은 그 보장이 없다 -
        // 저장 시점에 막지 않으면 ChecklistItemTemplate.isApplicableTo()가 이 토큰을 어떤
        // 매물유형과도 매칭시키지 못해 그 문항이 조용히 전체 매물유형에서 노출되지 않게 된다.
        assertThatThrownBy(() -> adminChecklistTemplateService.create(
                new AdminChecklistItemTemplateCreateRequest(
                        ChecklistCategory.AREA, "주차 공간이 충분한가요?", null, null,
                        ChecklistImportance.GENERAL, ChecklistItemType.CHECK, null, 1, "OFFICETEL,TYPO"
                )
        ))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> assertThat(((BusinessException) exception).getErrorCode())
                        .isEqualTo(ErrorCode.ADMIN_CHECKLIST_TEMPLATE_INVALID_PROPERTY_TYPE));
    }

    @Test
    @DisplayName("존재하는 문항을 수정하면 변경된 필드가 반영된다")
    void updateChangesExistingTemplateFields() {
        ChecklistItemTemplate existing = template(1L, 2, 1);
        when(checklistItemTemplateRepository.findById(1L)).thenReturn(Optional.of(existing));
        // 이 테스트는 active=false로의 전환 자체(마지막 활성 문항 보호)가 아니라 필드 반영을
        // 검증하는 것이 목적이므로, 다른 활성 문항이 남아있는 상태로 스텁해 그 보호에 걸리지 않게 한다.
        when(checklistItemTemplateRepository.findByActiveTrueOrderByDisplayOrderAsc())
                .thenReturn(List.of(existing, template(2L, 2, 2)));

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
    @DisplayName("존재하지 않는 매물유형으로 수정하면 ADMIN_CHECKLIST_TEMPLATE_INVALID_PROPERTY_TYPE 예외가 발생한다")
    void updateThrowsWhenApplicablePropertyTypeIsUnknown() {
        ChecklistItemTemplate existing = template(1L, 2, 1);
        when(checklistItemTemplateRepository.findById(1L)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> adminChecklistTemplateService.update(
                1L,
                new AdminChecklistItemTemplateUpdateRequest(
                        ChecklistCategory.SAFETY, "창문 잠금장치가 정상 작동하나요?", null, null,
                        ChecklistImportance.REQUIRED, ChecklistItemType.CHECK, null, 9, "TYPO", true
                )
        ))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> assertThat(((BusinessException) exception).getErrorCode())
                        .isEqualTo(ErrorCode.ADMIN_CHECKLIST_TEMPLATE_INVALID_PROPERTY_TYPE));
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
    @DisplayName("code에 맞지 않는 itemType으로 생성하면 ADMIN_CHECKLIST_TEMPLATE_INVALID_CODE 예외가 발생한다")
    void createThrowsWhenCodeAndItemTypeMismatch() {
        assertThatThrownBy(() -> adminChecklistTemplateService.create(
                new AdminChecklistItemTemplateCreateRequest(
                        ChecklistCategory.DOCUMENTS, "신탁등기가 되어 있나요?", null, null,
                        ChecklistImportance.REQUIRED, ChecklistItemType.CHECK,
                        ChecklistItemCode.TRUST_REGISTRATION, 1, null
                )
        ))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> assertThat(((BusinessException) exception).getErrorCode())
                        .isEqualTo(ErrorCode.ADMIN_CHECKLIST_TEMPLATE_INVALID_CODE));
    }

    @Test
    @DisplayName("이미 다른 활성 문항이 쓰는 code로 생성하면 ADMIN_CHECKLIST_TEMPLATE_DUPLICATE_CODE 예외가 발생한다")
    void createThrowsWhenCodeAlreadyUsedByAnotherActiveTemplate() {
        when(checklistItemTemplateRepository.findByCodeAndActiveTrue(ChecklistItemCode.TRUST_REGISTRATION))
                .thenReturn(List.of(templateWithCode(1L, ChecklistItemCode.TRUST_REGISTRATION, ChecklistItemType.YES_NO)));

        assertThatThrownBy(() -> adminChecklistTemplateService.create(
                new AdminChecklistItemTemplateCreateRequest(
                        ChecklistCategory.DOCUMENTS, "신탁등기가 되어 있나요? (중복)", null, null,
                        ChecklistImportance.REQUIRED, ChecklistItemType.YES_NO,
                        ChecklistItemCode.TRUST_REGISTRATION, 2, null
                )
        ))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> assertThat(((BusinessException) exception).getErrorCode())
                        .isEqualTo(ErrorCode.ADMIN_CHECKLIST_TEMPLATE_DUPLICATE_CODE));
    }

    @Test
    @DisplayName("자기 자신의 code를 그대로 유지하며 수정하면 중복으로 보지 않는다")
    void updateAllowsKeepingSameCodeOnSameTemplate() {
        ChecklistItemTemplate existing = templateWithCode(1L, ChecklistItemCode.TRUST_REGISTRATION, ChecklistItemType.YES_NO);
        when(checklistItemTemplateRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(checklistItemTemplateRepository.findByCodeAndActiveTrue(ChecklistItemCode.TRUST_REGISTRATION))
                .thenReturn(List.of(existing));

        AdminChecklistItemTemplateResponse result = adminChecklistTemplateService.update(
                1L,
                new AdminChecklistItemTemplateUpdateRequest(
                        ChecklistCategory.DOCUMENTS, "신탁등기가 되어 있나요? (문구 수정)", null, null,
                        ChecklistImportance.REQUIRED, ChecklistItemType.YES_NO,
                        ChecklistItemCode.TRUST_REGISTRATION, 1, null, true
                )
        );

        assertThat(result.code()).isEqualTo(ChecklistItemCode.TRUST_REGISTRATION);
    }

    @Test
    @DisplayName("다른 활성 문항이 쓰는 code로 수정하면 ADMIN_CHECKLIST_TEMPLATE_DUPLICATE_CODE 예외가 발생한다")
    void updateThrowsWhenCodeAlreadyUsedByAnotherActiveTemplate() {
        ChecklistItemTemplate other = templateWithCode(2L, ChecklistItemCode.TRUST_REGISTRATION, ChecklistItemType.YES_NO);
        ChecklistItemTemplate target = template(1L, 2, 1);
        when(checklistItemTemplateRepository.findById(1L)).thenReturn(Optional.of(target));
        when(checklistItemTemplateRepository.findByCodeAndActiveTrue(ChecklistItemCode.TRUST_REGISTRATION))
                .thenReturn(List.of(other));

        assertThatThrownBy(() -> adminChecklistTemplateService.update(
                1L,
                new AdminChecklistItemTemplateUpdateRequest(
                        ChecklistCategory.DOCUMENTS, "신탁등기가 되어 있나요?", null, null,
                        ChecklistImportance.REQUIRED, ChecklistItemType.YES_NO,
                        ChecklistItemCode.TRUST_REGISTRATION, 1, null, true
                )
        ))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> assertThat(((BusinessException) exception).getErrorCode())
                        .isEqualTo(ErrorCode.ADMIN_CHECKLIST_TEMPLATE_DUPLICATE_CODE));
    }

    @Test
    @DisplayName("존재하는 문항을 삭제하면 repository.delete가 호출된다")
    void deleteRemovesExistingTemplate() {
        ChecklistItemTemplate existing = template(1L, 2, 1);
        when(checklistItemTemplateRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(checklistItemTemplateRepository.count()).thenReturn(2L);
        // 이 테스트는 물리 삭제 자체를 검증하는 것이 목적이므로, 다른 활성 문항이 남아있는 상태로
        // 스텁해 마지막 활성 문항 보호(validateNotDeactivatingLastActiveTemplate)에 걸리지 않게 한다.
        when(checklistItemTemplateRepository.findByActiveTrueOrderByDisplayOrderAsc())
                .thenReturn(List.of(existing, template(2L, 2, 2)));

        adminChecklistTemplateService.delete(1L);

        verify(checklistItemTemplateRepository).delete(existing);
    }

    @Test
    @DisplayName("비활성 문항이 남아있어도 마지막 활성 문항을 삭제하려 하면 ADMIN_CHECKLIST_TEMPLATE_LAST_ITEM 예외가 발생한다")
    void deleteThrowsWhenDeletingTheLastActiveTemplateEvenIfInactiveTemplatesRemain() {
        // count() <= 1 검사는 "테이블 전체가 비어버리는 것"만 막는다 - 비활성 문항이 하나 더
        // 있으면 count()는 2를 반환해 통과하지만, 삭제 대상이 마지막 활성 문항이면 활성 문항이
        // 0개가 되는 회귀 테스트.
        ChecklistItemTemplate activeTemplate = template(1L, 2, 1);
        when(checklistItemTemplateRepository.findById(1L)).thenReturn(Optional.of(activeTemplate));
        when(checklistItemTemplateRepository.count()).thenReturn(2L);
        when(checklistItemTemplateRepository.findByActiveTrueOrderByDisplayOrderAsc())
                .thenReturn(List.of(activeTemplate));

        assertThatThrownBy(() -> adminChecklistTemplateService.delete(1L))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> assertThat(((BusinessException) exception).getErrorCode())
                        .isEqualTo(ErrorCode.ADMIN_CHECKLIST_TEMPLATE_LAST_ITEM));

        verify(checklistItemTemplateRepository, org.mockito.Mockito.never()).delete(any(ChecklistItemTemplate.class));
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

    @Test
    @DisplayName("마지막 활성 문항을 비활성화하려 하면 ADMIN_CHECKLIST_TEMPLATE_LAST_ITEM 예외가 발생한다")
    void updateThrowsWhenDeactivatingTheLastActiveTemplate() {
        // delete()는 마지막 문항 물리 삭제를 막지만, "숨기려면 active=false를 쓰라"는 안내와 달리
        // 그 경로 자체는 막혀 있지 않았던 회귀 테스트 - 이 경우 그 이후 생성되는 모든 유저
        // 체크리스트가 문항 0개로 만들어진다.
        ChecklistItemTemplate existing = template(1L, 2, 1);
        when(checklistItemTemplateRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(checklistItemTemplateRepository.findByActiveTrueOrderByDisplayOrderAsc()).thenReturn(List.of(existing));

        assertThatThrownBy(() -> adminChecklistTemplateService.update(
                1L,
                new AdminChecklistItemTemplateUpdateRequest(
                        ChecklistCategory.SAFETY, "창문 잠금장치가 정상 작동하나요?", null, null,
                        ChecklistImportance.REQUIRED, ChecklistItemType.CHECK, null, 9, null, false
                )
        ))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> assertThat(((BusinessException) exception).getErrorCode())
                        .isEqualTo(ErrorCode.ADMIN_CHECKLIST_TEMPLATE_LAST_ITEM));
    }

    @Test
    @DisplayName("다른 활성 문항이 남아있으면 비활성화해도 된다")
    void updateAllowsDeactivatingWhenOtherActiveTemplatesRemain() {
        ChecklistItemTemplate existing = template(1L, 2, 1);
        ChecklistItemTemplate other = template(2L, 2, 2);
        when(checklistItemTemplateRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(checklistItemTemplateRepository.findByActiveTrueOrderByDisplayOrderAsc())
                .thenReturn(List.of(existing, other));

        AdminChecklistItemTemplateResponse result = adminChecklistTemplateService.update(
                1L,
                new AdminChecklistItemTemplateUpdateRequest(
                        ChecklistCategory.SAFETY, "창문 잠금장치가 정상 작동하나요?", null, null,
                        ChecklistImportance.REQUIRED, ChecklistItemType.CHECK, null, 9, null, false
                )
        );

        assertThat(result.active()).isFalse();
    }

    @Test
    @DisplayName("마지막 남은 문항을 삭제하려 하면 ADMIN_CHECKLIST_TEMPLATE_LAST_ITEM 예외가 발생하고 삭제되지 않는다")
    void deleteThrowsWhenOnlyOneTemplateRemains() {
        ChecklistItemTemplate existing = template(1L, 2, 1);
        when(checklistItemTemplateRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(checklistItemTemplateRepository.count()).thenReturn(1L);

        assertThatThrownBy(() -> adminChecklistTemplateService.delete(1L))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> assertThat(((BusinessException) exception).getErrorCode())
                        .isEqualTo(ErrorCode.ADMIN_CHECKLIST_TEMPLATE_LAST_ITEM));

        verify(checklistItemTemplateRepository, org.mockito.Mockito.never()).delete(any(ChecklistItemTemplate.class));
    }
}
