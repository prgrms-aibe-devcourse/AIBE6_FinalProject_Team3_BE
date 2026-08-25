package com.algogyeyak.checklist.repository;

import com.algogyeyak.checklist.entity.Checklist;
import com.algogyeyak.checklist.entity.ChecklistCategory;
import com.algogyeyak.checklist.entity.ChecklistImportance;
import com.algogyeyak.checklist.entity.ChecklistItem;
import com.algogyeyak.checklist.entity.ChecklistItemType;
import com.algogyeyak.checklist.repository.ChecklistItemRepository.ChecklistProgressProjection;
import com.algogyeyak.property.entity.Property;
import com.algogyeyak.property.entity.PropertyType;
import com.algogyeyak.property.entity.TransactionType;
import com.algogyeyak.property.repository.PropertyRepository;
import com.algogyeyak.user.entity.User;
import com.algogyeyak.user.repository.UserRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 매물/체크리스트 목록에 진행률·주의 항목 개수를 붙이기 위한 집계 쿼리
 * ChecklistItemRepository.findProgressByUserId()를 검증한다.
 */
@DataJpaTest
class ChecklistItemRepositoryTest {

    @Autowired
    private ChecklistItemRepository checklistItemRepository;

    @Autowired
    private ChecklistRepository checklistRepository;

    @Autowired
    private PropertyRepository propertyRepository;

    @Autowired
    private UserRepository userRepository;

    private int userSeq = 0;

    private User saveUser() {
        userSeq++;
        return userRepository.save(User.createOAuthUser("test" + userSeq + "@example.com", "테스터" + userSeq, null));
    }

    private Property saveProperty(Long userId) {
        return propertyRepository.save(Property.builder()
                .userId(userId)
                .title("테스트 매물")
                .propertyType(PropertyType.OFFICETEL)
                .transactionType(TransactionType.JEONSE)
                .deposit(10_000_000L)
                .area(20.0)
                .build());
    }

    private Checklist saveChecklist(User user, Property property) {
        return checklistRepository.save(Checklist.builder()
                .user(user)
                .property(property)
                .templateVersion(1)
                .build());
    }

    private ChecklistItem saveCheckItem(Checklist checklist) {
        return saveItem(checklist, ChecklistImportance.GENERAL);
    }

    private ChecklistItem saveItem(Checklist checklist, ChecklistImportance importance) {
        return checklistItemRepository.save(ChecklistItem.builder()
                .checklist(checklist)
                .category(ChecklistCategory.INDOOR)
                .content("테스트 문항")
                .importance(importance)
                .itemType(ChecklistItemType.CHECK)
                .displayOrder(1)
                .build());
    }

    @Test
    void 체크한_항목_개수와_전체_항목_개수를_매물별로_집계한다() {
        User user = saveUser();
        Property property = saveProperty(user.getId());
        Checklist checklist = saveChecklist(user, property);
        ChecklistItem checked = saveCheckItem(checklist);
        checked.check(true);
        saveCheckItem(checklist);

        List<ChecklistProgressProjection> result = checklistItemRepository.findProgressByUserId(user.getId());

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getPropertyId()).isEqualTo(property.getId());
        assertThat(result.get(0).getTotalCount()).isEqualTo(2);
        assertThat(result.get(0).getCheckedCount()).isEqualTo(1);
    }

    @Test
    void issueFound으로_표시된_항목을_주의_항목_개수에_포함한다() {
        User user = saveUser();
        Property property = saveProperty(user.getId());
        Checklist checklist = saveChecklist(user, property);
        ChecklistItem issueItem = saveCheckItem(checklist);
        issueItem.markInsufficient("미흡함");
        saveCheckItem(checklist);

        List<ChecklistProgressProjection> result = checklistItemRepository.findProgressByUserId(user.getId());

        assertThat(result.get(0).getIssueCount()).isEqualTo(1);
    }

    @Test
    void 주의_항목이_없으면_issueCount는_0이다() {
        User user = saveUser();
        Property property = saveProperty(user.getId());
        Checklist checklist = saveChecklist(user, property);
        ChecklistItem checked = saveCheckItem(checklist);
        checked.check(true);

        List<ChecklistProgressProjection> result = checklistItemRepository.findProgressByUserId(user.getId());

        assertThat(result.get(0).getIssueCount()).isEqualTo(0);
    }

    @Test
    void 체크되지_않은_일반_항목_개수를_generalMissingCount로_집계한다() {
        User user = saveUser();
        Property property = saveProperty(user.getId());
        Checklist checklist = saveChecklist(user, property);
        saveItem(checklist, ChecklistImportance.GENERAL); // 미체크 일반 - 집계에 포함돼야 함
        ChecklistItem checkedGeneral = saveItem(checklist, ChecklistImportance.GENERAL);
        checkedGeneral.check(true); // 체크된 일반 - 집계에서 빠져야 함
        saveItem(checklist, ChecklistImportance.REQUIRED); // 미체크 필수 - GENERAL이 아니라 집계에서 빠져야 함

        List<ChecklistProgressProjection> result = checklistItemRepository.findProgressByUserId(user.getId());

        assertThat(result.get(0).getGeneralMissingCount()).isEqualTo(1);
    }

    @Test
    void 체크되지_않은_필수_항목_개수를_requiredMissingCount로_집계한다() {
        User user = saveUser();
        Property property = saveProperty(user.getId());
        Checklist checklist = saveChecklist(user, property);
        saveItem(checklist, ChecklistImportance.REQUIRED); // 미체크 필수 - 집계에 포함돼야 함
        ChecklistItem checkedRequired = saveItem(checklist, ChecklistImportance.REQUIRED);
        checkedRequired.check(true); // 체크된 필수 - 집계에서 빠져야 함
        saveItem(checklist, ChecklistImportance.GENERAL); // 미체크 일반 - REQUIRED가 아니라 집계에서 빠져야 함

        List<ChecklistProgressProjection> result = checklistItemRepository.findProgressByUserId(user.getId());

        assertThat(result.get(0).getRequiredMissingCount()).isEqualTo(1);
    }

    @Test
    void 다른_유저의_체크리스트_항목은_집계에_포함하지_않는다() {
        User user = saveUser();
        User other = saveUser();
        Property property = saveProperty(user.getId());
        Property otherProperty = saveProperty(other.getId());
        saveCheckItem(saveChecklist(user, property));
        saveCheckItem(saveChecklist(other, otherProperty));

        List<ChecklistProgressProjection> result = checklistItemRepository.findProgressByUserId(user.getId());

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getPropertyId()).isEqualTo(property.getId());
    }
}
