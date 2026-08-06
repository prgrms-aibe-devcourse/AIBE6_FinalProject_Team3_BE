package com.algogyeyak.checklist.service;

import com.algogyeyak.checklist.dto.ChecklistItemUpdateRequest;
import com.algogyeyak.checklist.dto.ChecklistOverviewResponse;
import com.algogyeyak.checklist.entity.Checklist;
import com.algogyeyak.checklist.entity.ChecklistCategory;
import com.algogyeyak.checklist.entity.ChecklistImportance;
import com.algogyeyak.checklist.entity.ChecklistItem;
import com.algogyeyak.checklist.entity.ChecklistItemTemplate;
import com.algogyeyak.checklist.entity.ChecklistItemType;
import com.algogyeyak.checklist.entity.ChecklistResult;
import com.algogyeyak.checklist.entity.ChecklistStatus;
import com.algogyeyak.checklist.repository.ChecklistItemTemplateRepository;
import com.algogyeyak.checklist.repository.ChecklistRepository;
import com.algogyeyak.global.error.ErrorCode;
import com.algogyeyak.global.exception.BusinessException;
import com.algogyeyak.property.entity.Property;
import com.algogyeyak.property.entity.PropertyStatus;
import com.algogyeyak.property.entity.PropertyType;
import com.algogyeyak.property.entity.TransactionType;
import com.algogyeyak.property.repository.PropertyRepository;
import com.algogyeyak.user.entity.User;
import com.algogyeyak.user.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("ChecklistService")
class ChecklistServiceTest {

    private final ChecklistRepository checklistRepository = mock(ChecklistRepository.class);
    private final ChecklistItemTemplateRepository templateRepository = mock(ChecklistItemTemplateRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final PropertyRepository propertyRepository = mock(PropertyRepository.class);
    private final ChecklistService checklistService =
            new ChecklistService(checklistRepository, templateRepository, userRepository, propertyRepository);

    private User user(Long id) {
        User user = User.createOAuthUser("test@example.com", "테스트유저", "http://img");
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    private Property property(Long id, Long ownerId) {
        Property property = Property.builder()
                .userId(ownerId)
                .title("테스트 매물")
                .propertyType(PropertyType.OFFICETEL)
                .transactionType(TransactionType.JEONSE)
                .deposit(10_000_000L)
                .area(20.0)
                .build();
        ReflectionTestUtils.setField(property, "id", id);
        return property;
    }

    @Test
    @DisplayName("이미 체크리스트가 있으면 새로 만들지 않고 기존 것을 반환한다")
    void returnsExistingChecklistWithoutCreatingNew() {
        Checklist existing = Checklist.createFrom(user(1L), property(10L, 1L), 1, List.of());
        when(checklistRepository.findByUserIdAndPropertyId(1L, 10L)).thenReturn(Optional.of(existing));

        Checklist result = checklistService.createOrGetChecklist(1L, 10L);

        assertThat(result).isEqualTo(existing);
        verify(checklistRepository, never()).save(any());
    }

    @Test
    @DisplayName("체크리스트가 없으면 활성 템플릿으로 새로 만들어 저장한다")
    void createsNewChecklistFromActiveTemplatesWhenNoneExists() {
        User user = user(1L);
        when(checklistRepository.findByUserIdAndPropertyId(1L, 10L)).thenReturn(Optional.empty());
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(propertyRepository.findById(10L)).thenReturn(Optional.of(property(10L, 1L)));
        when(templateRepository.findByActiveTrueOrderByDisplayOrderAsc()).thenReturn(List.of(
                ChecklistItemTemplate.builder()
                        .version(3)
                        .category(ChecklistCategory.INDOOR)
                        .content("누수 확인")
                        .importance(ChecklistImportance.GENERAL)
                        .itemType(ChecklistItemType.CHECK)
                        .displayOrder(1)
                        .active(true)
                        .build()
        ));
        when(checklistRepository.save(any(Checklist.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Checklist result = checklistService.createOrGetChecklist(1L, 10L);

        assertThat(result.getTemplateVersion()).isEqualTo(3);
        assertThat(result.getItems()).hasSize(1);
        verify(checklistRepository).save(any(Checklist.class));
    }

    @Test
    @DisplayName("체크리스트 생성 시 매물유형에 맞지 않는 문항은 제외된다")
    void createChecklistFiltersTemplatesByPropertyType() {
        User user = user(1L);
        when(checklistRepository.findByUserIdAndPropertyId(1L, 10L)).thenReturn(Optional.empty());
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(propertyRepository.findById(10L)).thenReturn(Optional.of(property(10L, 1L))); // OFFICETEL
        when(templateRepository.findByActiveTrueOrderByDisplayOrderAsc()).thenReturn(List.of(
                ChecklistItemTemplate.builder()
                        .version(2).category(ChecklistCategory.SAFETY)
                        .content("공동현관과 현관문 잠금장치가 모두 정상 작동하나요?")
                        .importance(ChecklistImportance.GENERAL).itemType(ChecklistItemType.CHECK)
                        .displayOrder(1).active(true)
                        .applicablePropertyTypes("OFFICETEL,MULTI_FAMILY")
                        .build(),
                ChecklistItemTemplate.builder()
                        .version(2).category(ChecklistCategory.SAFETY)
                        .content("현관문 잠금장치가 정상 작동하나요?")
                        .importance(ChecklistImportance.GENERAL).itemType(ChecklistItemType.CHECK)
                        .displayOrder(1).active(true)
                        .applicablePropertyTypes("DETACHED_HOUSE")
                        .build()
        ));
        when(checklistRepository.save(any(Checklist.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Checklist result = checklistService.createOrGetChecklist(1L, 10L);

        assertThat(result.getItems()).hasSize(1);
        assertThat(result.getItems().get(0).getContent()).isEqualTo("공동현관과 현관문 잠금장치가 모두 정상 작동하나요?");
    }

    @Test
    @DisplayName("존재하지 않는 매물이면 PROPERTY_NOT_FOUND 예외가 발생한다")
    void createChecklistThrowsWhenPropertyNotFound() {
        when(checklistRepository.findByUserIdAndPropertyId(1L, 10L)).thenReturn(Optional.empty());
        when(userRepository.findById(1L)).thenReturn(Optional.of(user(1L)));
        when(propertyRepository.findById(10L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> checklistService.createOrGetChecklist(1L, 10L))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception ->
                        assertThat(((BusinessException) exception).getErrorCode()).isEqualTo(ErrorCode.PROPERTY_NOT_FOUND)
                );
    }

    @Test
    @DisplayName("삭제된 매물이면 PROPERTY_NOT_FOUND 예외가 발생한다")
    void createChecklistThrowsWhenPropertyDeleted() {
        Property deletedProperty = property(10L, 1L);
        deletedProperty.delete();
        when(checklistRepository.findByUserIdAndPropertyId(1L, 10L)).thenReturn(Optional.empty());
        when(userRepository.findById(1L)).thenReturn(Optional.of(user(1L)));
        when(propertyRepository.findById(10L)).thenReturn(Optional.of(deletedProperty));

        assertThatThrownBy(() -> checklistService.createOrGetChecklist(1L, 10L))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception ->
                        assertThat(((BusinessException) exception).getErrorCode()).isEqualTo(ErrorCode.PROPERTY_NOT_FOUND)
                );
    }

    @Test
    @DisplayName("본인 소유가 아닌 매물이면 PROPERTY_ACCESS_DENIED 예외가 발생한다")
    void createChecklistThrowsWhenNotPropertyOwner() {
        when(checklistRepository.findByUserIdAndPropertyId(1L, 10L)).thenReturn(Optional.empty());
        when(userRepository.findById(1L)).thenReturn(Optional.of(user(1L)));
        when(propertyRepository.findById(10L)).thenReturn(Optional.of(property(10L, 999L)));

        assertThatThrownBy(() -> checklistService.createOrGetChecklist(1L, 10L))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception ->
                        assertThat(((BusinessException) exception).getErrorCode()).isEqualTo(ErrorCode.PROPERTY_ACCESS_DENIED)
                );
    }

    // 정렬(최종 점검일 기준 내림차순)은 이제 DB 쿼리(ChecklistRepository.findOverviewByUserId())가
    // 담당한다 - ChecklistRepositoryTest에서 검증함. 여기서는 리포지토리가 돌려준 Page<Object[]>를
    // PageResponse<ChecklistOverviewResponse>로 올바르게 매핑하는지만 확인한다.
    @Test
    @DisplayName("매물마다 체크리스트 유무에 따라 상태를 매칭해 페이지 응답을 반환한다")
    void listMyChecklistsMatchesEachPropertyWithItsChecklist() {
        Property started = property(10L, 1L);
        Property notStarted = property(20L, 1L);

        Checklist checklist = checklistWithOneCheckItem(user(1L));
        ReflectionTestUtils.setField(checklist, "id", 100L);
        checklist.getItems().get(0).check(true);
        checklist.refreshStatus();

        org.springframework.data.domain.Pageable pageable = org.springframework.data.domain.PageRequest.of(0, 20);
        org.springframework.data.domain.Page<Object[]> page = new org.springframework.data.domain.PageImpl<>(
                List.of(new Object[]{started, checklist}, new Object[]{notStarted, null}),
                pageable, 2);
        when(checklistRepository.findOverviewByUserId(1L, PropertyStatus.ACTIVE, pageable)).thenReturn(page);

        com.algogyeyak.global.response.PageResponse<ChecklistOverviewResponse> result =
                checklistService.listMyChecklists(1L, pageable);

        assertThat(result.content()).hasSize(2);
        assertThat(result.content().get(0).propertyId()).isEqualTo(10L);
        assertThat(result.content().get(0).checklistId()).isEqualTo(100L);
        assertThat(result.content().get(0).status()).isEqualTo(ChecklistStatus.COMPLETED);
        assertThat(result.content().get(1).propertyId()).isEqualTo(20L);
        assertThat(result.content().get(1).checklistId()).isNull();
        assertThat(result.content().get(1).status()).isEqualTo(ChecklistStatus.NOT_STARTED);
        assertThat(result.totalElements()).isEqualTo(2);
    }

    @Test
    @DisplayName("매물이 하나도 없으면 빈 페이지를 반환한다")
    void listMyChecklistsReturnsEmptyPageWhenNoProperties() {
        org.springframework.data.domain.Pageable pageable = org.springframework.data.domain.PageRequest.of(0, 20);
        when(checklistRepository.findOverviewByUserId(1L, PropertyStatus.ACTIVE, pageable))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(), pageable, 0));

        com.algogyeyak.global.response.PageResponse<ChecklistOverviewResponse> result =
                checklistService.listMyChecklists(1L, pageable);

        assertThat(result.content()).isEmpty();
        assertThat(result.totalElements()).isZero();
    }

    @Test
    @DisplayName("클라이언트가 sort를 지정해도 무시하고 page/size만 리포지토리에 넘긴다")
    void listMyChecklistsIgnoresClientSuppliedSort() {
        org.springframework.data.domain.Pageable clientPageable = org.springframework.data.domain.PageRequest.of(
                1, 10, org.springframework.data.domain.Sort.by("propertyId"));
        org.springframework.data.domain.Pageable expectedPageable = org.springframework.data.domain.PageRequest.of(1, 10);
        when(checklistRepository.findOverviewByUserId(1L, PropertyStatus.ACTIVE, expectedPageable))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(), expectedPageable, 0));

        checklistService.listMyChecklists(1L, clientPageable);

        verify(checklistRepository).findOverviewByUserId(1L, PropertyStatus.ACTIVE, expectedPageable);
    }

    private Checklist checklistWithOneCheckItem(User user) {
        ChecklistItemTemplate template = ChecklistItemTemplate.builder()
                .version(1)
                .category(ChecklistCategory.INDOOR)
                .content("누수 확인")
                .importance(ChecklistImportance.GENERAL)
                .itemType(ChecklistItemType.CHECK)
                .displayOrder(1)
                .active(true)
                .build();
        return Checklist.createFrom(user, property(10L, 1L), 1, List.of(template));
    }

    @Test
    @DisplayName("userNote 요청을 보내면 항목이 미흡으로 표시되고 메모가 저장된다")
    void updateChecklistItemMarksInsufficientWithNote() {
        User user = user(1L);
        ChecklistItemTemplate generalTemplate = ChecklistItemTemplate.builder()
                .version(1).category(ChecklistCategory.INDOOR).content("누수 확인")
                .importance(ChecklistImportance.GENERAL).itemType(ChecklistItemType.CHECK)
                .displayOrder(1).active(true).build();
        ChecklistItemTemplate requiredTemplate = ChecklistItemTemplate.builder()
                .version(1).category(ChecklistCategory.DOCUMENTS).content("등기부등본 확인")
                .importance(ChecklistImportance.REQUIRED).itemType(ChecklistItemType.CHECK)
                .displayOrder(2).active(true).build();
        Checklist checklist = Checklist.createFrom(user, property(10L, 1L), 1, List.of(generalTemplate, requiredTemplate));
        ReflectionTestUtils.setField(checklist, "id", 100L);
        ChecklistItem item = checklist.getItems().get(0);
        ReflectionTestUtils.setField(item, "id", 200L);
        when(checklistRepository.findById(100L)).thenReturn(Optional.of(checklist));

        ChecklistItem result = checklistService.updateChecklistItem(
                1L, 100L, 200L, new ChecklistItemUpdateRequest(null, null, "환기구 막힘")
        );

        assertThat(result.isChecked()).isTrue();
        assertThat(result.getUserNote()).isEqualTo("환기구 막힘");
        // REQUIRED 항목(등기부등본 확인)이 아직 미체크 상태라 COMPLETED가 아닌 IN_PROGRESS여야 한다.
        assertThat(checklist.getStatus()).isEqualTo(ChecklistStatus.IN_PROGRESS);
    }

    @Test
    @DisplayName("checked=true 요청을 보내면 완료로 전환되고 메모는 지워진다")
    void updateChecklistItemCompletesAndClearsNote() {
        User user = user(1L);
        Checklist checklist = checklistWithOneCheckItem(user);
        ReflectionTestUtils.setField(checklist, "id", 100L);
        ChecklistItem item = checklist.getItems().get(0);
        ReflectionTestUtils.setField(item, "id", 200L);
        item.markInsufficient("이전 메모");
        when(checklistRepository.findById(100L)).thenReturn(Optional.of(checklist));

        ChecklistItem result = checklistService.updateChecklistItem(
                1L, 100L, 200L, new ChecklistItemUpdateRequest(true, null, null)
        );

        assertThat(result.isChecked()).isTrue();
        assertThat(result.getUserNote()).isNull();
    }

    @Test
    @DisplayName("존재하지 않는 체크리스트면 CHECKLIST_NOT_FOUND 예외가 발생한다")
    void updateChecklistItemThrowsWhenChecklistNotFound() {
        when(checklistRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                checklistService.updateChecklistItem(1L, 999L, 1L, new ChecklistItemUpdateRequest(true, null, null))
        )
                .isInstanceOf(BusinessException.class)
                .satisfies(exception ->
                        assertThat(((BusinessException) exception).getErrorCode()).isEqualTo(ErrorCode.CHECKLIST_NOT_FOUND)
                );
    }

    @Test
    @DisplayName("본인 소유가 아닌 체크리스트면 PROPERTY_ACCESS_DENIED 예외가 발생한다")
    void updateChecklistItemThrowsWhenNotOwner() {
        User owner = user(1L);
        Checklist checklist = checklistWithOneCheckItem(owner);
        ReflectionTestUtils.setField(checklist, "id", 100L);
        when(checklistRepository.findById(100L)).thenReturn(Optional.of(checklist));

        assertThatThrownBy(() ->
                checklistService.updateChecklistItem(999L, 100L, 1L, new ChecklistItemUpdateRequest(true, null, null))
        )
                .isInstanceOf(BusinessException.class)
                .satisfies(exception ->
                        assertThat(((BusinessException) exception).getErrorCode()).isEqualTo(ErrorCode.PROPERTY_ACCESS_DENIED)
                );
    }

    @Test
    @DisplayName("존재하지 않는 항목이면 CHECKLIST_ITEM_NOT_FOUND 예외가 발생한다")
    void updateChecklistItemThrowsWhenItemNotFound() {
        User user = user(1L);
        Checklist checklist = checklistWithOneCheckItem(user);
        ReflectionTestUtils.setField(checklist, "id", 100L);
        ReflectionTestUtils.setField(checklist.getItems().get(0), "id", 200L);
        when(checklistRepository.findById(100L)).thenReturn(Optional.of(checklist));

        assertThatThrownBy(() ->
                checklistService.updateChecklistItem(1L, 100L, 999L, new ChecklistItemUpdateRequest(true, null, null))
        )
                .isInstanceOf(BusinessException.class)
                .satisfies(exception ->
                        assertThat(((BusinessException) exception).getErrorCode()).isEqualTo(ErrorCode.CHECKLIST_ITEM_NOT_FOUND)
                );
    }

    @Test
    @DisplayName("checked/value/userNote가 전부 비어있으면 BAD_REQUEST 예외가 발생한다")
    void updateChecklistItemThrowsWhenNoFieldProvided() {
        User user = user(1L);
        Checklist checklist = checklistWithOneCheckItem(user);
        ReflectionTestUtils.setField(checklist, "id", 100L);
        ChecklistItem item = checklist.getItems().get(0);
        ReflectionTestUtils.setField(item, "id", 200L);
        when(checklistRepository.findById(100L)).thenReturn(Optional.of(checklist));

        assertThatThrownBy(() ->
                checklistService.updateChecklistItem(1L, 100L, 200L, new ChecklistItemUpdateRequest(null, null, null))
        )
                .isInstanceOf(BusinessException.class)
                .satisfies(exception ->
                        assertThat(((BusinessException) exception).getErrorCode()).isEqualTo(ErrorCode.BAD_REQUEST)
                );
    }

    @Test
    @DisplayName("본인 체크리스트의 결과를 조회하면 computeResult()와 동일한 결과를 반환한다")
    void getChecklistResultReturnsComputedResult() {
        User user = user(1L);
        Checklist checklist = checklistWithOneCheckItem(user);
        ReflectionTestUtils.setField(checklist, "id", 100L);
        checklist.getItems().get(0).check(true);
        checklist.refreshStatus();
        when(checklistRepository.findById(100L)).thenReturn(Optional.of(checklist));

        ChecklistResult result = checklistService.getChecklistResult(1L, 100L);

        assertThat(result).isEqualTo(checklist.computeResult());
        assertThat(result.checkedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("존재하지 않는 체크리스트의 결과를 조회하면 CHECKLIST_NOT_FOUND 예외가 발생한다")
    void getChecklistResultThrowsWhenChecklistNotFound() {
        when(checklistRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> checklistService.getChecklistResult(1L, 999L))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception ->
                        assertThat(((BusinessException) exception).getErrorCode()).isEqualTo(ErrorCode.CHECKLIST_NOT_FOUND)
                );
    }

    @Test
    @DisplayName("본인 소유가 아닌 체크리스트의 결과를 조회하면 PROPERTY_ACCESS_DENIED 예외가 발생한다")
    void getChecklistResultThrowsWhenNotOwner() {
        User owner = user(1L);
        Checklist checklist = checklistWithOneCheckItem(owner);
        ReflectionTestUtils.setField(checklist, "id", 100L);
        when(checklistRepository.findById(100L)).thenReturn(Optional.of(checklist));

        assertThatThrownBy(() -> checklistService.getChecklistResult(999L, 100L))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception ->
                        assertThat(((BusinessException) exception).getErrorCode()).isEqualTo(ErrorCode.PROPERTY_ACCESS_DENIED)
                );
    }

    @Test
    @DisplayName("매물이 삭제된 상태면 결과 조회 시 CHECKLIST_NOT_FOUND 예외가 발생한다")
    void getChecklistResultThrowsWhenPropertyDeleted() {
        User user = user(1L);
        Property deletedProperty = property(10L, 1L);
        deletedProperty.delete();
        Checklist checklist = Checklist.createFrom(user, deletedProperty, 1, List.of());
        ReflectionTestUtils.setField(checklist, "id", 100L);
        when(checklistRepository.findById(100L)).thenReturn(Optional.of(checklist));

        assertThatThrownBy(() -> checklistService.getChecklistResult(1L, 100L))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception ->
                        assertThat(((BusinessException) exception).getErrorCode()).isEqualTo(ErrorCode.CHECKLIST_NOT_FOUND)
                );
    }

    @Test
    @DisplayName("본인 매물의 체크리스트를 조회하면 문항까지 포함해 반환한다")
    void getChecklistReturnsChecklistWithItems() {
        User user = user(1L);
        Checklist checklist = checklistWithOneCheckItem(user);
        when(propertyRepository.findById(10L)).thenReturn(Optional.of(property(10L, 1L)));
        when(checklistRepository.findByPropertyId(10L)).thenReturn(Optional.of(checklist));

        Checklist result = checklistService.getChecklist(1L, 10L);

        assertThat(result).isEqualTo(checklist);
        assertThat(result.getItems()).hasSize(1);
    }

    @Test
    @DisplayName("존재하지 않는 매물을 조회하면 PROPERTY_NOT_FOUND 예외가 발생한다")
    void getChecklistThrowsWhenPropertyNotFound() {
        when(propertyRepository.findById(10L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> checklistService.getChecklist(1L, 10L))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception ->
                        assertThat(((BusinessException) exception).getErrorCode()).isEqualTo(ErrorCode.PROPERTY_NOT_FOUND)
                );
    }

    @Test
    @DisplayName("매물은 있지만 체크리스트가 없으면 CHECKLIST_NOT_FOUND 예외가 발생한다")
    void getChecklistThrowsWhenNoneExists() {
        when(propertyRepository.findById(10L)).thenReturn(Optional.of(property(10L, 1L)));
        when(checklistRepository.findByPropertyId(10L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> checklistService.getChecklist(1L, 10L))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception ->
                        assertThat(((BusinessException) exception).getErrorCode()).isEqualTo(ErrorCode.CHECKLIST_NOT_FOUND)
                );
    }

    @Test
    @DisplayName("본인 소유가 아닌 매물을 조회하면 PROPERTY_ACCESS_DENIED 예외가 발생한다")
    void getChecklistThrowsWhenNotOwner() {
        when(propertyRepository.findById(10L)).thenReturn(Optional.of(property(10L, 999L)));

        assertThatThrownBy(() -> checklistService.getChecklist(1L, 10L))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception ->
                        assertThat(((BusinessException) exception).getErrorCode()).isEqualTo(ErrorCode.PROPERTY_ACCESS_DENIED)
                );
    }

    @Test
    @DisplayName("매물이 삭제된 상태면 PROPERTY_NOT_FOUND 예외가 발생한다")
    void getChecklistThrowsWhenPropertyDeleted() {
        Property deletedProperty = property(10L, 1L);
        deletedProperty.delete();
        when(propertyRepository.findById(10L)).thenReturn(Optional.of(deletedProperty));

        assertThatThrownBy(() -> checklistService.getChecklist(1L, 10L))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception ->
                        assertThat(((BusinessException) exception).getErrorCode()).isEqualTo(ErrorCode.PROPERTY_NOT_FOUND)
                );
    }

    @Test
    @DisplayName("매물이 삭제된 상태면 항목을 수정할 수 없고 CHECKLIST_NOT_FOUND 예외가 발생한다")
    void updateChecklistItemThrowsWhenPropertyDeleted() {
        User user = user(1L);
        Property deletedProperty = property(10L, 1L);
        deletedProperty.delete();
        ChecklistItemTemplate template = ChecklistItemTemplate.builder()
                .version(1).category(ChecklistCategory.INDOOR).content("누수 확인")
                .importance(ChecklistImportance.GENERAL).itemType(ChecklistItemType.CHECK)
                .displayOrder(1).active(true).build();
        Checklist checklist = Checklist.createFrom(user, deletedProperty, 1, List.of(template));
        ReflectionTestUtils.setField(checklist, "id", 100L);
        ChecklistItem item = checklist.getItems().get(0);
        ReflectionTestUtils.setField(item, "id", 200L);
        when(checklistRepository.findById(100L)).thenReturn(Optional.of(checklist));

        assertThatThrownBy(() ->
                checklistService.updateChecklistItem(1L, 100L, 200L, new ChecklistItemUpdateRequest(true, null, null))
        )
                .isInstanceOf(BusinessException.class)
                .satisfies(exception ->
                        assertThat(((BusinessException) exception).getErrorCode()).isEqualTo(ErrorCode.CHECKLIST_NOT_FOUND)
                );
    }
}
