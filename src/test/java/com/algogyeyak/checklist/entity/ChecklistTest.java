package com.algogyeyak.checklist.entity;

import com.algogyeyak.property.entity.Property;
import com.algogyeyak.property.entity.PropertyType;
import com.algogyeyak.property.entity.TransactionType;
import com.algogyeyak.user.entity.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Checklist")
class ChecklistTest {

    private User testUser() {
        return User.createOAuthUser("test@example.com", "테스트유저", "http://img");
    }

    private Property testProperty(Long id) {
        Property property = Property.builder()
                .userId(1L)
                .propertyType(PropertyType.OFFICETEL)
                .transactionType(TransactionType.JEONSE)
                .deposit(10_000_000L)
                .area(20.0)
                .build();
        ReflectionTestUtils.setField(property, "id", id);
        return property;
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

        Checklist checklist = Checklist.createFrom(user, testProperty(10L), 1, templates);

        assertThat(checklist.getUser()).isEqualTo(user);
        assertThat(checklist.getProperty().getId()).isEqualTo(10L);
        assertThat(checklist.getTemplateVersion()).isEqualTo(1);
        assertThat(checklist.getStatus()).isEqualTo(ChecklistStatus.NOT_STARTED);

        assertThat(checklist.getItems()).hasSize(2);
        ChecklistItem firstItem = checklist.getItems().get(0);
        assertThat(firstItem.getCategory()).isEqualTo(ChecklistCategory.INDOOR);
        assertThat(firstItem.getContent()).isEqualTo("누수 확인");
        assertThat(firstItem.isChecked()).isFalse();
        assertThat(firstItem.isIssueFound()).isFalse();
    }

    @Test
    @DisplayName("refreshStatus()는 체크된 항목이 없으면 NOT_STARTED로 유지한다")
    void refreshStatusStaysNotStartedWithNoCheckedItems() {
        User user = testUser();
        List<ChecklistItemTemplate> templates = List.of(
                template(ChecklistCategory.INDOOR, "누수 확인", ChecklistImportance.GENERAL, ChecklistItemType.CHECK, null, 1)
        );
        Checklist checklist = Checklist.createFrom(user, testProperty(10L), 1, templates);

        checklist.refreshStatus();

        assertThat(checklist.getStatus()).isEqualTo(ChecklistStatus.NOT_STARTED);
    }

    @Test
    @DisplayName("refreshStatus()는 REQUIRED 항목을 모두 체크하면 COMPLETED가 된다")
    void refreshStatusBecomesCompletedWhenAllRequiredChecked() {
        User user = testUser();
        List<ChecklistItemTemplate> templates = List.of(
                template(ChecklistCategory.DOCUMENTS, "등기부등본 확인", ChecklistImportance.REQUIRED, ChecklistItemType.CHECK, null, 1),
                template(ChecklistCategory.INDOOR, "누수 확인(일반)", ChecklistImportance.GENERAL, ChecklistItemType.CHECK, null, 2)
        );
        Checklist checklist = Checklist.createFrom(user, testProperty(10L), 1, templates);
        checklist.getItems().get(0).check(true);

        checklist.refreshStatus();

        assertThat(checklist.getStatus()).isEqualTo(ChecklistStatus.COMPLETED);
    }

    @Test
    @DisplayName("refreshStatus()는 REQUIRED 항목이 남아있으면 IN_PROGRESS가 된다")
    void refreshStatusBecomesInProgressWhenSomeRequiredRemain() {
        User user = testUser();
        List<ChecklistItemTemplate> templates = List.of(
                template(ChecklistCategory.DOCUMENTS, "등기부등본 확인", ChecklistImportance.REQUIRED, ChecklistItemType.CHECK, null, 1),
                template(ChecklistCategory.DOCUMENTS, "계약조건 재확인", ChecklistImportance.REQUIRED, ChecklistItemType.CHECK, null, 2)
        );
        Checklist checklist = Checklist.createFrom(user, testProperty(10L), 1, templates);
        checklist.getItems().get(0).check(true);

        checklist.refreshStatus();

        assertThat(checklist.getStatus()).isEqualTo(ChecklistStatus.IN_PROGRESS);
    }

    @Test
    @DisplayName("아무것도 체크 안 했으면 NOT_STARTED 상태와 시작 안내 메시지를 반환한다")
    void computeResultReturnsStartGuideWhenNotStarted() {
        User user = testUser();
        List<ChecklistItemTemplate> templates = List.of(
                template(ChecklistCategory.DOCUMENTS, "등기부등본 확인", ChecklistImportance.REQUIRED, ChecklistItemType.CHECK, null, 1),
                template(ChecklistCategory.INDOOR, "누수 확인", ChecklistImportance.GENERAL, ChecklistItemType.CHECK, null, 2)
        );
        Checklist checklist = Checklist.createFrom(user, testProperty(10L), 1, templates);

        ChecklistResult result = checklist.computeResult();

        assertThat(result.status()).isEqualTo(ChecklistStatus.NOT_STARTED);
        assertThat(result.checkedCount()).isEqualTo(0);
        assertThat(result.totalCount()).isEqualTo(2);
        assertThat(result.requiredMissingCount()).isEqualTo(1);
        assertThat(result.issueCount()).isEqualTo(0);
        assertThat(result.message()).isEqualTo("체크리스트를 시작해보세요");
    }

    @Test
    @DisplayName("일부만 체크했으면 진행 중 상태로, 안내 메시지 없이 개수를 반환한다")
    void computeResultReturnsCountsWithoutMessageWhenInProgress() {
        User user = testUser();
        List<ChecklistItemTemplate> templates = List.of(
                template(ChecklistCategory.DOCUMENTS, "등기부등본 확인", ChecklistImportance.REQUIRED, ChecklistItemType.CHECK, null, 1),
                template(ChecklistCategory.DOCUMENTS, "계약조건 재확인", ChecklistImportance.REQUIRED, ChecklistItemType.CHECK, null, 2),
                template(ChecklistCategory.INDOOR, "누수 확인", ChecklistImportance.GENERAL, ChecklistItemType.CHECK, null, 3)
        );
        Checklist checklist = Checklist.createFrom(user, testProperty(10L), 1, templates);
        checklist.getItems().get(0).check(true);
        checklist.getItems().get(2).markInsufficient("환기구 막힘");
        checklist.refreshStatus();

        ChecklistResult result = checklist.computeResult();

        assertThat(result.status()).isEqualTo(ChecklistStatus.IN_PROGRESS);
        assertThat(result.checkedCount()).isEqualTo(2);
        assertThat(result.totalCount()).isEqualTo(3);
        assertThat(result.requiredMissingCount()).isEqualTo(1);
        assertThat(result.issueCount()).isEqualTo(1);
        assertThat(result.message()).isNull();
    }

    @Test
    @DisplayName("필수 항목을 모두 체크했으면 완료 상태를 반환한다")
    void computeResultReturnsCompletedWhenAllRequiredChecked() {
        User user = testUser();
        List<ChecklistItemTemplate> templates = List.of(
                template(ChecklistCategory.DOCUMENTS, "등기부등본 확인", ChecklistImportance.REQUIRED, ChecklistItemType.CHECK, null, 1)
        );
        Checklist checklist = Checklist.createFrom(user, testProperty(10L), 1, templates);
        checklist.getItems().get(0).check(true);
        checklist.refreshStatus();

        ChecklistResult result = checklist.computeResult();

        assertThat(result.status()).isEqualTo(ChecklistStatus.COMPLETED);
        assertThat(result.requiredMissingCount()).isEqualTo(0);
        assertThat(result.message()).isNull();
    }
}
