package com.algogyeyak.checklist.entity;

import com.algogyeyak.user.entity.User;
import com.algogyeyak.user.enums.AuthProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Checklist")
class ChecklistTest {

    private User testUser() {
        return User.createOAuthUser("test@example.com", "테스트유저", "http://img", AuthProvider.KAKAO, "123");
    }

    private ChecklistItemTemplate template(
            ChecklistCategory category,
            String content,
            ChecklistImportance importance,
            ChecklistItemType itemType,
            ChecklistItemCode code,
            int displayOrder
    ) {
        return ChecklistItemTemplate.builder()
                .version(1)
                .category(category)
                .content(content)
                .importance(importance)
                .itemType(itemType)
                .code(code)
                .displayOrder(displayOrder)
                .active(true)
                .build();
    }

    @Test
    @DisplayName("createFrom()은 템플릿 목록을 스냅샷 복사해 체크리스트와 문항들을 함께 만든다")
    void createFromCopiesTemplatesIntoItems() {
        User user = testUser();
        List<ChecklistItemTemplate> templates = List.of(
                template(ChecklistCategory.INDOOR, "누수 확인", ChecklistImportance.GENERAL, ChecklistItemType.CHECK, null, 1),
                template(ChecklistCategory.DOCUMENTS, "신탁등기 여부", ChecklistImportance.REQUIRED, ChecklistItemType.YES_NO, ChecklistItemCode.TRUST_REGISTRATION, 2)
        );

        Checklist checklist = Checklist.createFrom(user, 10L, 1, templates);

        assertThat(checklist.getUser()).isEqualTo(user);
        assertThat(checklist.getPropertyId()).isEqualTo(10L);
        assertThat(checklist.getTemplateVersion()).isEqualTo(1);
        assertThat(checklist.getStatus()).isEqualTo(ChecklistStatus.NOT_STARTED);

        assertThat(checklist.getItems()).hasSize(2);
        ChecklistItem firstItem = checklist.getItems().get(0);
        assertThat(firstItem.getCategory()).isEqualTo(ChecklistCategory.INDOOR);
        assertThat(firstItem.getContent()).isEqualTo("누수 확인");
        assertThat(firstItem.isChecked()).isFalse();
        assertThat(firstItem.isIssueFound()).isFalse();
    }
}
