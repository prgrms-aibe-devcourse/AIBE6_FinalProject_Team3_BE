package com.algogyeyak.checklist.service;

import com.algogyeyak.admin.entity.AdminAuditAction;
import com.algogyeyak.admin.service.AdminAuditLogger;
import com.algogyeyak.checklist.dto.AdminChecklistItemTemplateCreateRequest;
import com.algogyeyak.checklist.dto.AdminChecklistItemTemplateImageCreateRequest;
import com.algogyeyak.checklist.dto.AdminChecklistItemTemplateImageResponse;
import com.algogyeyak.checklist.dto.AdminChecklistItemTemplateResponse;
import com.algogyeyak.checklist.dto.AdminChecklistItemTemplateUpdateRequest;
import com.algogyeyak.checklist.entity.ChecklistCategory;
import com.algogyeyak.checklist.entity.ChecklistImportance;
import com.algogyeyak.checklist.entity.ChecklistItemCode;
import com.algogyeyak.checklist.entity.ChecklistItemTemplate;
import com.algogyeyak.checklist.entity.ChecklistItemTemplateImage;
import com.algogyeyak.checklist.entity.ChecklistItemType;
import com.algogyeyak.checklist.repository.ChecklistItemTemplateImageRepository;
import com.algogyeyak.checklist.repository.ChecklistItemTemplateRepository;
import com.algogyeyak.global.error.ErrorCode;
import com.algogyeyak.global.exception.BusinessException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AdminAuditLogger는 mock으로 대체한다 - 실제 감사 로그 저장(JSON 직렬화, 이메일 스냅샷 조회 등)은
 * AdminAuditLogger 자체의 관심사이고, 여기서는 각 액션이 실패했을 때 감사 로그가 남지 않는지(정책:
 * 거부된 변경에는 기록도 남지 않는다)만 함께 확인한다.
 */
@DisplayName("AdminChecklistTemplateService")
class AdminChecklistTemplateServiceTest {

    private static final Long ACTOR_ID = 100L;
    private static final String ACTOR_EMAIL = "actor@example.com";

    private final ChecklistItemTemplateRepository checklistItemTemplateRepository = mock(ChecklistItemTemplateRepository.class);
    private final ChecklistItemTemplateImageRepository checklistItemTemplateImageRepository = mock(ChecklistItemTemplateImageRepository.class);
    private final AdminAuditLogger adminAuditLogger = mock(AdminAuditLogger.class);
    private final AdminChecklistTemplateService adminChecklistTemplateService = new AdminChecklistTemplateService(
            checklistItemTemplateRepository, checklistItemTemplateImageRepository, adminAuditLogger);

    private ChecklistItemTemplate template(Long id, int version, int displayOrder) {
        return template(id, version, displayOrder, true);
    }

    private ChecklistItemTemplate template(Long id, int version, int displayOrder, boolean active) {
        ChecklistItemTemplate template = ChecklistItemTemplate.builder()
                .version(version)
                .category(ChecklistCategory.INDOOR)
                .content("누수 확인")
                .importance(ChecklistImportance.GENERAL)
                .itemType(ChecklistItemType.CHECK)
                .displayOrder(displayOrder)
                .active(active)
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

    private void verifyNoAuditLog() {
        verify(adminAuditLogger, never()).log(any(), any(), any(), any(), any());
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
    @DisplayName("생성 시 활성 문항 중 가장 높은 버전을 그대로 물려받는다")
    void createInheritsHighestExistingVersion() {
        when(checklistItemTemplateRepository.findByActiveTrueOrderByDisplayOrderAsc())
                .thenReturn(List.of(template(1L, 2, 1), template(2L, 3, 2)));
        when(checklistItemTemplateRepository.save(any(ChecklistItemTemplate.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        AdminChecklistItemTemplateResponse result = adminChecklistTemplateService.create(
                ACTOR_ID, ACTOR_EMAIL,
                new AdminChecklistItemTemplateCreateRequest(
                        ChecklistCategory.AREA, "주차 공간이 충분한가요?", null, null,
                        ChecklistImportance.GENERAL, ChecklistItemType.CHECK, null, null, 30, null
                )
        );

        assertThat(result.version()).isEqualTo(3);
        assertThat(result.active()).isTrue();
        assertThat(result.content()).isEqualTo("주차 공간이 충분한가요?");
    }

    @Test
    @DisplayName("회귀 테스트 - 비활성 문항의 버전이 활성 문항보다 높아도 무시하고 활성 문항 기준으로 버전을 매긴다")
    void createIgnoresHigherVersionFromInactiveTemplates() {
        // 과거에 비활성화된 문항(version=5)이 현재 활성 문항(version=2)보다 버전이 높게 남아있는
        // 상황 - 전체(findAllByOrderByDisplayOrderAsc) 기준으로 최대값을 구하면 새 문항이 5를
        // 물려받아, 실제로 쓰이는 활성 문항 집합(version=2)과 어긋난 값을 갖게 된다.
        when(checklistItemTemplateRepository.findByActiveTrueOrderByDisplayOrderAsc())
                .thenReturn(List.of(template(1L, 2, 1, true)));
        when(checklistItemTemplateRepository.save(any(ChecklistItemTemplate.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        AdminChecklistItemTemplateResponse result = adminChecklistTemplateService.create(
                ACTOR_ID, ACTOR_EMAIL,
                new AdminChecklistItemTemplateCreateRequest(
                        ChecklistCategory.AREA, "주차 공간이 충분한가요?", null, null,
                        ChecklistImportance.GENERAL, ChecklistItemType.CHECK, null, null, 30, null
                )
        );

        assertThat(result.version()).isEqualTo(2);
        verify(checklistItemTemplateRepository, never()).findAllByOrderByDisplayOrderAsc();
    }

    @Test
    @DisplayName("기존 문항이 하나도 없으면 버전 1로 생성한다")
    void createDefaultsToVersionOneWhenNoTemplatesExist() {
        when(checklistItemTemplateRepository.findByActiveTrueOrderByDisplayOrderAsc()).thenReturn(List.of());
        when(checklistItemTemplateRepository.save(any(ChecklistItemTemplate.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        AdminChecklistItemTemplateResponse result = adminChecklistTemplateService.create(
                ACTOR_ID, ACTOR_EMAIL,
                new AdminChecklistItemTemplateCreateRequest(
                        ChecklistCategory.AREA, "주차 공간이 충분한가요?", null, null,
                        ChecklistImportance.GENERAL, ChecklistItemType.CHECK, null, null, 1, null
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
                ACTOR_ID, ACTOR_EMAIL,
                new AdminChecklistItemTemplateCreateRequest(
                        ChecklistCategory.AREA, "주차 공간이 충분한가요?", null, null,
                        ChecklistImportance.GENERAL, ChecklistItemType.CHECK, null, null, 1, "OFFICETEL,TYPO"
                )
        ))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> assertThat(((BusinessException) exception).getErrorCode())
                        .isEqualTo(ErrorCode.ADMIN_CHECKLIST_TEMPLATE_INVALID_PROPERTY_TYPE));
        verifyNoAuditLog();
    }

    @Test
    @DisplayName("trailing comma로 생긴 빈 토큰은 존재하지 않는 매물유형으로 취급하지 않는다")
    void createAllowsTrailingCommaInApplicablePropertyTypes() {
        // 회귀 테스트 - split(",")가 "OFFICETEL,"에서 만드는 빈 문자열 토큰을 거르지 않으면, 오타가
        // 아니라 단순 구분자 습관 차이일 뿐인데도 ADMIN_CHECKLIST_TEMPLATE_INVALID_PROPERTY_TYPE로
        // 거부됐다.
        when(checklistItemTemplateRepository.findByActiveTrueOrderByDisplayOrderAsc()).thenReturn(List.of());
        when(checklistItemTemplateRepository.save(any(ChecklistItemTemplate.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        AdminChecklistItemTemplateResponse result = adminChecklistTemplateService.create(
                ACTOR_ID, ACTOR_EMAIL,
                new AdminChecklistItemTemplateCreateRequest(
                        ChecklistCategory.AREA, "주차 공간이 충분한가요?", null, null,
                        ChecklistImportance.GENERAL, ChecklistItemType.CHECK, null, null, 1, "OFFICETEL,"
                )
        );

        assertThat(result.applicablePropertyTypes()).isEqualTo("OFFICETEL,");
    }

    @Test
    @DisplayName("구분자만 있고 유효한 매물유형 토큰이 하나도 없으면 예외가 발생한다")
    void createThrowsWhenApplicablePropertyTypesHasOnlySeparators() {
        // 회귀 테스트 - trailing comma 허용 수정이 만든 새 버그. "," 하나만 있으면 필터링 후 토큰이
        // 0개가 되어 검증은 통과하지만, null이 아니라서 ChecklistItemTemplate.isApplicableTo()가
        // "전체 적용"으로 봐주지 않고 빈 배열과 어떤 매물유형도 매칭시키지 못해 그 문항이 모든
        // 매물유형에서 조용히 숨겨진다 - 이 검증 메서드가 원래 막으려던 바로 그 실패 모드다.
        assertThatThrownBy(() -> adminChecklistTemplateService.create(
                ACTOR_ID, ACTOR_EMAIL,
                new AdminChecklistItemTemplateCreateRequest(
                        ChecklistCategory.AREA, "주차 공간이 충분한가요?", null, null,
                        ChecklistImportance.GENERAL, ChecklistItemType.CHECK, null, null, 1, " , "
                )
        ))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> assertThat(((BusinessException) exception).getErrorCode())
                        .isEqualTo(ErrorCode.ADMIN_CHECKLIST_TEMPLATE_INVALID_PROPERTY_TYPE));
        verifyNoAuditLog();
    }

    @Test
    @DisplayName("MULTIPLE_CHOICE 문항을 options와 함께 생성할 수 있다")
    void createSavesMultipleChoiceOptions() {
        when(checklistItemTemplateRepository.findByActiveTrueOrderByDisplayOrderAsc()).thenReturn(List.of());
        when(checklistItemTemplateRepository.save(any(ChecklistItemTemplate.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        AdminChecklistItemTemplateResponse result = adminChecklistTemplateService.create(
                ACTOR_ID, ACTOR_EMAIL,
                new AdminChecklistItemTemplateCreateRequest(
                        ChecklistCategory.INDOOR, "보일러 종류가 무엇인가요?", null, null,
                        ChecklistImportance.GENERAL, ChecklistItemType.MULTIPLE_CHOICE,
                        "가스보일러,기름보일러,전기보일러,지역난방", null, 1, null
                )
        );

        assertThat(result.options()).isEqualTo("가스보일러,기름보일러,전기보일러,지역난방");
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
                ACTOR_ID, ACTOR_EMAIL,
                1L,
                new AdminChecklistItemTemplateUpdateRequest(
                        ChecklistCategory.SAFETY, "창문 잠금장치가 정상 작동하나요?", "안내", null,
                        ChecklistImportance.REQUIRED, ChecklistItemType.CHECK, null, null, 9, null, false
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
    @DisplayName("회귀 테스트 - 비활성 문항을 재활성화하면 낡은 버전이 아니라 현재 활성 집합 기준 버전으로 재정렬된다")
    void updateRealignsVersionWhenReactivatingTemplateWithStaleVersion() {
        // 과거에 버전 5일 때 비활성화된 문항 - 그 사이 다른 문항들은 create()를 거치며 버전 2로
        // 올라와 있다. 이 문항을 그대로 재활성화하면(버전을 안 건드리면) 활성 집합에 버전 2와 5가
        // 섞여, ChecklistService.createChecklist()가 그 이후 임의의 버전을 새 체크리스트에 찍는다.
        ChecklistItemTemplate stale = template(1L, 5, 1, false);
        ChecklistItemTemplate currentlyActive = template(2L, 2, 2);
        when(checklistItemTemplateRepository.findById(1L)).thenReturn(Optional.of(stale));
        when(checklistItemTemplateRepository.findByActiveTrueOrderByDisplayOrderAsc())
                .thenReturn(List.of(currentlyActive));

        AdminChecklistItemTemplateResponse result = adminChecklistTemplateService.update(
                ACTOR_ID, ACTOR_EMAIL,
                1L,
                new AdminChecklistItemTemplateUpdateRequest(
                        ChecklistCategory.SAFETY, "창문 잠금장치가 정상 작동하나요?", null, null,
                        ChecklistImportance.REQUIRED, ChecklistItemType.CHECK, null, null, 9, null, true
                )
        );

        assertThat(result.active()).isTrue();
        assertThat(result.version()).isEqualTo(2);
    }

    @Test
    @DisplayName("존재하지 않는 매물유형으로 수정하면 ADMIN_CHECKLIST_TEMPLATE_INVALID_PROPERTY_TYPE 예외가 발생한다")
    void updateThrowsWhenApplicablePropertyTypeIsUnknown() {
        ChecklistItemTemplate existing = template(1L, 2, 1);
        when(checklistItemTemplateRepository.findById(1L)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> adminChecklistTemplateService.update(
                ACTOR_ID, ACTOR_EMAIL,
                1L,
                new AdminChecklistItemTemplateUpdateRequest(
                        ChecklistCategory.SAFETY, "창문 잠금장치가 정상 작동하나요?", null, null,
                        ChecklistImportance.REQUIRED, ChecklistItemType.CHECK, null, null, 9, "TYPO", true
                )
        ))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> assertThat(((BusinessException) exception).getErrorCode())
                        .isEqualTo(ErrorCode.ADMIN_CHECKLIST_TEMPLATE_INVALID_PROPERTY_TYPE));
        verifyNoAuditLog();
    }

    @Test
    @DisplayName("존재하지 않는 문항을 수정하면 ADMIN_CHECKLIST_TEMPLATE_NOT_FOUND 예외가 발생한다")
    void updateThrowsWhenTemplateNotFound() {
        when(checklistItemTemplateRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> adminChecklistTemplateService.update(
                ACTOR_ID, ACTOR_EMAIL,
                999L,
                new AdminChecklistItemTemplateUpdateRequest(
                        ChecklistCategory.AREA, "내용", null, null,
                        ChecklistImportance.GENERAL, ChecklistItemType.CHECK, null, null, 1, null, true
                )
        ))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> assertThat(((BusinessException) exception).getErrorCode())
                        .isEqualTo(ErrorCode.ADMIN_CHECKLIST_TEMPLATE_NOT_FOUND));
        verifyNoAuditLog();
    }

    @Test
    @DisplayName("code에 맞지 않는 itemType으로 생성하면 ADMIN_CHECKLIST_TEMPLATE_INVALID_CODE 예외가 발생한다")
    void createThrowsWhenCodeAndItemTypeMismatch() {
        assertThatThrownBy(() -> adminChecklistTemplateService.create(
                ACTOR_ID, ACTOR_EMAIL,
                new AdminChecklistItemTemplateCreateRequest(
                        ChecklistCategory.DOCUMENTS, "신탁등기가 되어 있나요?", null, null,
                        ChecklistImportance.REQUIRED, ChecklistItemType.CHECK, null,
                        ChecklistItemCode.TRUST_REGISTRATION, 1, null
                )
        ))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> assertThat(((BusinessException) exception).getErrorCode())
                        .isEqualTo(ErrorCode.ADMIN_CHECKLIST_TEMPLATE_INVALID_CODE));
        verifyNoAuditLog();
    }

    @Test
    @DisplayName("이미 다른 활성 문항이 쓰는 code로 생성하면 ADMIN_CHECKLIST_TEMPLATE_DUPLICATE_CODE 예외가 발생한다")
    void createThrowsWhenCodeAlreadyUsedByAnotherActiveTemplate() {
        when(checklistItemTemplateRepository.findByCodeAndActiveTrue(ChecklistItemCode.TRUST_REGISTRATION))
                .thenReturn(List.of(templateWithCode(1L, ChecklistItemCode.TRUST_REGISTRATION, ChecklistItemType.YES_NO)));

        assertThatThrownBy(() -> adminChecklistTemplateService.create(
                ACTOR_ID, ACTOR_EMAIL,
                new AdminChecklistItemTemplateCreateRequest(
                        ChecklistCategory.DOCUMENTS, "신탁등기가 되어 있나요? (중복)", null, null,
                        ChecklistImportance.REQUIRED, ChecklistItemType.YES_NO, null,
                        ChecklistItemCode.TRUST_REGISTRATION, 2, null
                )
        ))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> assertThat(((BusinessException) exception).getErrorCode())
                        .isEqualTo(ErrorCode.ADMIN_CHECKLIST_TEMPLATE_DUPLICATE_CODE));
        verifyNoAuditLog();
    }

    @Test
    @DisplayName("자기 자신의 code를 그대로 유지하며 수정하면 중복으로 보지 않는다")
    void updateAllowsKeepingSameCodeOnSameTemplate() {
        ChecklistItemTemplate existing = templateWithCode(1L, ChecklistItemCode.TRUST_REGISTRATION, ChecklistItemType.YES_NO);
        when(checklistItemTemplateRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(checklistItemTemplateRepository.findByCodeAndActiveTrue(ChecklistItemCode.TRUST_REGISTRATION))
                .thenReturn(List.of(existing));

        AdminChecklistItemTemplateResponse result = adminChecklistTemplateService.update(
                ACTOR_ID, ACTOR_EMAIL,
                1L,
                new AdminChecklistItemTemplateUpdateRequest(
                        ChecklistCategory.DOCUMENTS, "신탁등기가 되어 있나요? (문구 수정)", null, null,
                        ChecklistImportance.REQUIRED, ChecklistItemType.YES_NO, null,
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
                ACTOR_ID, ACTOR_EMAIL,
                1L,
                new AdminChecklistItemTemplateUpdateRequest(
                        ChecklistCategory.DOCUMENTS, "신탁등기가 되어 있나요?", null, null,
                        ChecklistImportance.REQUIRED, ChecklistItemType.YES_NO, null,
                        ChecklistItemCode.TRUST_REGISTRATION, 1, null, true
                )
        ))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> assertThat(((BusinessException) exception).getErrorCode())
                        .isEqualTo(ErrorCode.ADMIN_CHECKLIST_TEMPLATE_DUPLICATE_CODE));
        verifyNoAuditLog();
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

        adminChecklistTemplateService.delete(ACTOR_ID, ACTOR_EMAIL, 1L);

        verify(checklistItemTemplateRepository).delete(existing);
    }

    @Test
    @DisplayName("예시 이미지가 있는 문항을 삭제하면 템플릿 삭제 전에 이미지부터 지운다 (FK 위반 회귀 테스트)")
    void deleteRemovesAssociatedImagesBeforeDeletingTemplate() {
        // 회귀 테스트 - template_id는 nullable=false FK라, 이미지가 하나라도 남아있는 채로 템플릿만
        // 지우면 DataIntegrityViolationException(500)이 났다. deleteByTemplateId()를 먼저 호출해야
        // FK 위반 없이 삭제가 끝난다.
        ChecklistItemTemplate existing = template(1L, 2, 1);
        when(checklistItemTemplateRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(checklistItemTemplateRepository.count()).thenReturn(2L);
        when(checklistItemTemplateRepository.findByActiveTrueOrderByDisplayOrderAsc())
                .thenReturn(List.of(existing, template(2L, 2, 2)));

        adminChecklistTemplateService.delete(ACTOR_ID, ACTOR_EMAIL, 1L);

        InOrder order = inOrder(checklistItemTemplateImageRepository, checklistItemTemplateRepository);
        order.verify(checklistItemTemplateImageRepository).deleteByTemplateId(1L);
        order.verify(checklistItemTemplateRepository).delete(existing);
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

        assertThatThrownBy(() -> adminChecklistTemplateService.delete(ACTOR_ID, ACTOR_EMAIL, 1L))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> assertThat(((BusinessException) exception).getErrorCode())
                        .isEqualTo(ErrorCode.ADMIN_CHECKLIST_TEMPLATE_LAST_ITEM));

        verify(checklistItemTemplateRepository, never()).delete(any(ChecklistItemTemplate.class));
        verifyNoAuditLog();
    }

    @Test
    @DisplayName("존재하지 않는 문항을 삭제하면 ADMIN_CHECKLIST_TEMPLATE_NOT_FOUND 예외가 발생한다")
    void deleteThrowsWhenTemplateNotFound() {
        when(checklistItemTemplateRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> adminChecklistTemplateService.delete(ACTOR_ID, ACTOR_EMAIL, 999L))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> assertThat(((BusinessException) exception).getErrorCode())
                        .isEqualTo(ErrorCode.ADMIN_CHECKLIST_TEMPLATE_NOT_FOUND));
        verifyNoAuditLog();
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
                ACTOR_ID, ACTOR_EMAIL,
                1L,
                new AdminChecklistItemTemplateUpdateRequest(
                        ChecklistCategory.SAFETY, "창문 잠금장치가 정상 작동하나요?", null, null,
                        ChecklistImportance.REQUIRED, ChecklistItemType.CHECK, null, null, 9, null, false
                )
        ))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> assertThat(((BusinessException) exception).getErrorCode())
                        .isEqualTo(ErrorCode.ADMIN_CHECKLIST_TEMPLATE_LAST_ITEM));
        verifyNoAuditLog();
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
                ACTOR_ID, ACTOR_EMAIL,
                1L,
                new AdminChecklistItemTemplateUpdateRequest(
                        ChecklistCategory.SAFETY, "창문 잠금장치가 정상 작동하나요?", null, null,
                        ChecklistImportance.REQUIRED, ChecklistItemType.CHECK, null, null, 9, null, false
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

        assertThatThrownBy(() -> adminChecklistTemplateService.delete(ACTOR_ID, ACTOR_EMAIL, 1L))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> assertThat(((BusinessException) exception).getErrorCode())
                        .isEqualTo(ErrorCode.ADMIN_CHECKLIST_TEMPLATE_LAST_ITEM));

        verify(checklistItemTemplateRepository, never()).delete(any(ChecklistItemTemplate.class));
        verifyNoAuditLog();
    }

    private ChecklistItemTemplateImage image(Long id, ChecklistItemTemplate template, String imageUrl, int displayOrder) {
        ChecklistItemTemplateImage image = ChecklistItemTemplateImage.builder()
                .template(template)
                .imageUrl(imageUrl)
                .displayOrder(displayOrder)
                .build();
        ReflectionTestUtils.setField(image, "id", id);
        return image;
    }

    @Test
    @DisplayName("문항의 이미지 목록을 표시순서대로 반환한다")
    void listImagesReturnsImagesInDisplayOrder() {
        ChecklistItemTemplate template = template(1L, 3, 1);
        when(checklistItemTemplateRepository.findById(1L)).thenReturn(Optional.of(template));
        when(checklistItemTemplateImageRepository.findByTemplateIdOrderByDisplayOrderAsc(1L))
                .thenReturn(List.of(
                        image(10L, template, "https://example.com/1.jpg", 1),
                        image(11L, template, "https://example.com/2.jpg", 2)
                ));

        List<AdminChecklistItemTemplateImageResponse> result = adminChecklistTemplateService.listImages(1L);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).imageUrl()).isEqualTo("https://example.com/1.jpg");
    }

    @Test
    @DisplayName("존재하지 않는 문항의 이미지 목록을 조회하면 ADMIN_CHECKLIST_TEMPLATE_NOT_FOUND 예외가 발생한다")
    void listImagesThrowsWhenTemplateNotFound() {
        when(checklistItemTemplateRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> adminChecklistTemplateService.listImages(999L))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> assertThat(((BusinessException) exception).getErrorCode())
                        .isEqualTo(ErrorCode.ADMIN_CHECKLIST_TEMPLATE_NOT_FOUND));
    }

    @Test
    @DisplayName("이미지를 추가하면 기존 이미지 중 가장 큰 표시순서 다음 값으로 저장된다")
    void addImageAssignsNextDisplayOrder() {
        ChecklistItemTemplate template = template(1L, 3, 1);
        when(checklistItemTemplateRepository.findById(1L)).thenReturn(Optional.of(template));
        when(checklistItemTemplateImageRepository.findByTemplateIdOrderByDisplayOrderAsc(1L))
                .thenReturn(List.of(image(10L, template, "https://example.com/1.jpg", 1)));
        when(checklistItemTemplateImageRepository.save(any(ChecklistItemTemplateImage.class)))
                .thenAnswer(invocation -> {
                    ChecklistItemTemplateImage arg = invocation.getArgument(0);
                    ReflectionTestUtils.setField(arg, "id", 20L);
                    return arg;
                });

        AdminChecklistItemTemplateImageResponse result = adminChecklistTemplateService.addImage(
                ACTOR_ID, ACTOR_EMAIL,1L, new AdminChecklistItemTemplateImageCreateRequest("https://example.com/2.jpg"));

        assertThat(result.imageUrl()).isEqualTo("https://example.com/2.jpg");
        assertThat(result.displayOrder()).isEqualTo(2);
        // 회귀 테스트(2026-08-20) - targetType이 CHECKLIST_TEMPLATE이므로 targetId는 새로 생성된
        // 이미지 id(20L)가 아니라 소속 템플릿 id(1L)여야 한다.
        verify(adminAuditLogger).log(any(), any(), eq(AdminAuditAction.ADD_CHECKLIST_TEMPLATE_IMAGE), eq(1L), any());
    }

    @Test
    @DisplayName("존재하지 않는 문항에 이미지를 추가하면 ADMIN_CHECKLIST_TEMPLATE_NOT_FOUND 예외가 발생하고 저장되지 않는다")
    void addImageThrowsWhenTemplateNotFound() {
        when(checklistItemTemplateRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> adminChecklistTemplateService.addImage(
                ACTOR_ID, ACTOR_EMAIL,999L, new AdminChecklistItemTemplateImageCreateRequest("https://example.com/1.jpg")))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> assertThat(((BusinessException) exception).getErrorCode())
                        .isEqualTo(ErrorCode.ADMIN_CHECKLIST_TEMPLATE_NOT_FOUND));
        verify(checklistItemTemplateImageRepository, never()).save(any());
        verifyNoAuditLog();
    }

    // 회귀 테스트 - ChecklistItemTemplateImage의 (template_id, display_order) 유니크 제약(체크리스트
    // 시더의 동시 기동 중복 삽입을 막기 위해 추가됨)이 관리자 두 명이 거의 동시에 이미지를 추가하는
    // 레이스도 함께 막는다. 그 예외가 그대로 새어나가면 사용자 친화적인 응답 없이 500으로 올라가므로,
    // 재시도를 안내하는 409(ADMIN_CHECKLIST_TEMPLATE_IMAGE_ORDER_CONFLICT)로 변환돼야 한다.
    @Test
    @DisplayName("동시에 같은 표시순서로 이미지가 저장되면(다른 관리자와의 레이스) 재시도를 안내하는 409로 변환된다")
    void addImageTranslatesDisplayOrderConflictToBusinessException() {
        ChecklistItemTemplate template = template(1L, 3, 1);
        when(checklistItemTemplateRepository.findById(1L)).thenReturn(Optional.of(template));
        when(checklistItemTemplateImageRepository.findByTemplateIdOrderByDisplayOrderAsc(1L))
                .thenReturn(List.of(image(10L, template, "https://example.com/1.jpg", 1)));
        when(checklistItemTemplateImageRepository.save(any(ChecklistItemTemplateImage.class)))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException(
                        "Duplicate entry for key 'uk_checklist_item_template_image_template_display_order'"));

        assertThatThrownBy(() -> adminChecklistTemplateService.addImage(
                ACTOR_ID, ACTOR_EMAIL, 1L, new AdminChecklistItemTemplateImageCreateRequest("https://example.com/2.jpg")))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> assertThat(((BusinessException) exception).getErrorCode())
                        .isEqualTo(ErrorCode.ADMIN_CHECKLIST_TEMPLATE_IMAGE_ORDER_CONFLICT));
        verifyNoAuditLog();
    }

    @Test
    @DisplayName("이미지를 삭제한다")
    void deleteImageRemovesImage() {
        ChecklistItemTemplate template = template(1L, 3, 1);
        ChecklistItemTemplateImage image = image(10L, template, "https://example.com/1.jpg", 1);
        when(checklistItemTemplateImageRepository.findById(10L)).thenReturn(Optional.of(image));

        adminChecklistTemplateService.deleteImage(ACTOR_ID, ACTOR_EMAIL, 1L, 10L);

        verify(checklistItemTemplateImageRepository).delete(image);
        // 회귀 테스트(2026-08-20) - targetId는 삭제된 이미지 id(10L)가 아니라 소속 템플릿 id(1L)여야 한다.
        verify(adminAuditLogger).log(any(), any(), eq(AdminAuditAction.DELETE_CHECKLIST_TEMPLATE_IMAGE), eq(1L), any());
    }

    @Test
    @DisplayName("존재하지 않는 이미지를 삭제하면 ADMIN_CHECKLIST_TEMPLATE_IMAGE_NOT_FOUND 예외가 발생한다")
    void deleteImageThrowsWhenImageNotFound() {
        when(checklistItemTemplateImageRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> adminChecklistTemplateService.deleteImage(ACTOR_ID, ACTOR_EMAIL, 1L, 999L))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> assertThat(((BusinessException) exception).getErrorCode())
                        .isEqualTo(ErrorCode.ADMIN_CHECKLIST_TEMPLATE_IMAGE_NOT_FOUND));
        verifyNoAuditLog();
    }

    @Test
    @DisplayName("다른 문항 소유의 이미지를 삭제하려 하면 ADMIN_CHECKLIST_TEMPLATE_IMAGE_NOT_FOUND 예외가 발생한다")
    void deleteImageThrowsWhenImageBelongsToDifferentTemplate() {
        ChecklistItemTemplate otherTemplate = template(2L, 3, 1);
        ChecklistItemTemplateImage image = image(10L, otherTemplate, "https://example.com/1.jpg", 1);
        when(checklistItemTemplateImageRepository.findById(10L)).thenReturn(Optional.of(image));

        assertThatThrownBy(() -> adminChecklistTemplateService.deleteImage(ACTOR_ID, ACTOR_EMAIL, 1L, 10L))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> assertThat(((BusinessException) exception).getErrorCode())
                        .isEqualTo(ErrorCode.ADMIN_CHECKLIST_TEMPLATE_IMAGE_NOT_FOUND));
        verify(checklistItemTemplateImageRepository, never()).delete(any());
        verifyNoAuditLog();
    }
}
