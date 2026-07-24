package com.algogyeyak.checklist.service;

import com.algogyeyak.checklist.entity.Checklist;
import com.algogyeyak.checklist.entity.ChecklistCategory;
import com.algogyeyak.checklist.entity.ChecklistImportance;
import com.algogyeyak.checklist.entity.ChecklistItemTemplate;
import com.algogyeyak.checklist.entity.ChecklistItemType;
import com.algogyeyak.checklist.repository.ChecklistItemTemplateRepository;
import com.algogyeyak.checklist.repository.ChecklistRepository;
import com.algogyeyak.user.entity.User;
import com.algogyeyak.user.enums.AuthProvider;
import com.algogyeyak.user.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
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
}
