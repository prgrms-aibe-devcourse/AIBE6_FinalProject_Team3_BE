package com.algogyeyak.checklist.service;

import com.algogyeyak.checklist.dto.ChecklistItemUpdateRequest;
import com.algogyeyak.checklist.entity.Checklist;
import com.algogyeyak.checklist.entity.ChecklistCategory;
import com.algogyeyak.checklist.entity.ChecklistImportance;
import com.algogyeyak.checklist.entity.ChecklistItem;
import com.algogyeyak.checklist.entity.ChecklistItemTemplate;
import com.algogyeyak.checklist.entity.ChecklistItemType;
import com.algogyeyak.checklist.entity.ChecklistStatus;
import com.algogyeyak.checklist.repository.ChecklistItemTemplateRepository;
import com.algogyeyak.checklist.repository.ChecklistRepository;
import com.algogyeyak.global.error.ErrorCode;
import com.algogyeyak.global.exception.BusinessException;
import com.algogyeyak.user.entity.User;
import com.algogyeyak.user.enums.AuthProvider;
import com.algogyeyak.user.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

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
    private final ChecklistService checklistService =
            new ChecklistService(checklistRepository, templateRepository, userRepository);

    private User user(Long id) {
        User user = User.createOAuthUser("test@example.com", "테스트유저", "http://img", AuthProvider.KAKAO, "123");
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    @Test
    @DisplayName("이미 체크리스트가 있으면 새로 만들지 않고 기존 것을 반환한다")
    void returnsExistingChecklistWithoutCreatingNew() {
        Checklist existing = Checklist.createFrom(user(1L), 10L, 1, List.of());
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
        return Checklist.createFrom(user, 10L, 1, List.of(template));
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
        Checklist checklist = Checklist.createFrom(user, 10L, 1, List.of(generalTemplate, requiredTemplate));
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
    @DisplayName("존재하지 않는 체크리스트면 NOT_FOUND 예외가 발생한다")
    void updateChecklistItemThrowsWhenChecklistNotFound() {
        when(checklistRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                checklistService.updateChecklistItem(1L, 999L, 1L, new ChecklistItemUpdateRequest(true, null, null))
        )
                .isInstanceOf(BusinessException.class)
                .satisfies(exception ->
                        assertThat(((BusinessException) exception).getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND)
                );
    }

    @Test
    @DisplayName("본인 소유가 아닌 체크리스트면 FORBIDDEN 예외가 발생한다")
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
                        assertThat(((BusinessException) exception).getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN)
                );
    }

    @Test
    @DisplayName("존재하지 않는 항목이면 NOT_FOUND 예외가 발생한다")
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
                        assertThat(((BusinessException) exception).getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND)
                );
    }

    @Test
    @DisplayName("checked/value/userNote가 전부 비어있으면 INVALID_INPUT 예외가 발생한다")
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
                        assertThat(((BusinessException) exception).getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT)
                );
    }
}
