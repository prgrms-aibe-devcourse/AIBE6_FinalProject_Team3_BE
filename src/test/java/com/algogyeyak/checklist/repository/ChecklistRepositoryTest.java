package com.algogyeyak.checklist.repository;

import com.algogyeyak.checklist.entity.Checklist;
import com.algogyeyak.property.entity.Property;
import com.algogyeyak.property.entity.PropertyStatus;
import com.algogyeyak.property.entity.PropertyType;
import com.algogyeyak.property.entity.TransactionType;
import com.algogyeyak.property.repository.PropertyRepository;
import com.algogyeyak.user.entity.User;
import com.algogyeyak.user.repository.UserRepository;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "내 체크리스트 목록"(GET /checklists) 페이지네이션의 근거가 되는
 * ChecklistRepository.findOverviewByUserId()를 검증한다 - Property LEFT JOIN Checklist를
 * COALESCE(checklist.updatedAt, property.updatedAt) 기준 내림차순으로 페이지네이션하는 쿼리.
 *
 * updatedAt(@LastModifiedDate)은 JPA auditing(@EnableJpaAuditing)에 의존하는데, @DataJpaTest는
 * 이 애플리케이션의 슬라이스 컨텍스트에 JpaAuditingConfig를 자동으로 포함하지 않아 단독 실행 시
 * 항상 null로 남는다(전체 스위트로 돌릴 때만 다른 테스트가 먼저 로드한 컨텍스트를 캐시로 우연히
 * 재사용해 동작하는 것처럼 보임 - PropertyRepositoryTest도 같은 문제를 안고 있음, 별도 이슈).
 * 정렬을 검증하는 테스트는 이 auditing에 의존하지 않도록 ReflectionTestUtils로 updatedAt을
 * 직접 지정한다.
 */
@DataJpaTest
class ChecklistRepositoryTest {

    @Autowired
    private ChecklistRepository checklistRepository;

    @Autowired
    private PropertyRepository propertyRepository;

    @Autowired
    private UserRepository userRepository;

    // email/nickname 둘 다 unique 제약이 있어, 한 테스트 안에서 유저를 여러 명 만들 때 값이
    // 겹치지 않도록 매 호출마다 다른 값을 준다.
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

    private void touchUpdatedAt(Property property, LocalDateTime updatedAt) {
        ReflectionTestUtils.setField(property, "updatedAt", updatedAt);
        propertyRepository.saveAndFlush(property);
    }

    private void touchUpdatedAt(Checklist checklist, LocalDateTime updatedAt) {
        ReflectionTestUtils.setField(checklist, "updatedAt", updatedAt);
        checklistRepository.saveAndFlush(checklist);
    }

    @Test
    void 체크리스트가_있는_매물과_없는_매물을_함께_반환한다() {
        User user = saveUser();
        Property withChecklist = saveProperty(user.getId());
        saveChecklist(user, withChecklist);
        Property withoutChecklist = saveProperty(user.getId());

        Page<Object[]> result = checklistRepository.findOverviewByUserId(
                user.getId(), PropertyStatus.ACTIVE, PageRequest.of(0, 20));

        assertThat(result.getContent()).hasSize(2);
        assertThat(result.getTotalElements()).isEqualTo(2);
    }

    @Test
    void 다른_유저의_매물은_포함하지_않는다() {
        User user = saveUser();
        User other = saveUser();
        saveProperty(user.getId());
        saveProperty(other.getId());

        Page<Object[]> result = checklistRepository.findOverviewByUserId(
                user.getId(), PropertyStatus.ACTIVE, PageRequest.of(0, 20));

        assertThat(result.getContent()).hasSize(1);
    }

    @Test
    void 삭제된_매물은_포함하지_않는다() {
        User user = saveUser();
        saveProperty(user.getId());
        Property deleted = saveProperty(user.getId());
        deleted.delete();
        propertyRepository.save(deleted);

        Page<Object[]> result = checklistRepository.findOverviewByUserId(
                user.getId(), PropertyStatus.ACTIVE, PageRequest.of(0, 20));

        assertThat(result.getContent()).hasSize(1);
    }

    @Test
    void 페이지_크기만큼만_반환하고_전체_개수는_따로_유지한다() {
        User user = saveUser();
        saveProperty(user.getId());
        saveProperty(user.getId());
        saveProperty(user.getId());

        Page<Object[]> result = checklistRepository.findOverviewByUserId(
                user.getId(), PropertyStatus.ACTIVE, PageRequest.of(0, 2));

        assertThat(result.getContent()).hasSize(2);
        assertThat(result.getTotalElements()).isEqualTo(3);
        assertThat(result.getTotalPages()).isEqualTo(2);
    }

    // 체크리스트가 없는 매물끼리는 lastCheckedAt이 property.updatedAt으로 대체되므로,
    // 더 최근에 수정된 매물이 먼저 나와야 한다.
    @Test
    void 체크리스트가_없으면_매물_자체의_수정시각_기준_내림차순으로_정렬한다() {
        User user = saveUser();
        Property older = saveProperty(user.getId());
        Property newer = saveProperty(user.getId());
        touchUpdatedAt(older, LocalDateTime.of(2026, 1, 1, 0, 0));
        touchUpdatedAt(newer, LocalDateTime.of(2026, 6, 1, 0, 0));

        Page<Object[]> result = checklistRepository.findOverviewByUserId(
                user.getId(), PropertyStatus.ACTIVE, PageRequest.of(0, 20));

        assertThat(((Property) result.getContent().get(0)[0]).getId()).isEqualTo(newer.getId());
        assertThat(((Property) result.getContent().get(1)[0]).getId()).isEqualTo(older.getId());
    }

    // 체크리스트가 있으면 그 updatedAt이 매물 자체의 updatedAt보다 우선한다 - 매물 자체는 오래됐어도
    // 최근에 점검한 매물이 더 위로 와야 한다.
    @Test
    void 체크리스트가_있으면_체크리스트_수정시각이_매물_수정시각보다_우선한다() {
        User user = saveUser();
        Property withRecentChecklist = saveProperty(user.getId());
        Checklist checklist = saveChecklist(user, withRecentChecklist);
        Property withoutChecklist = saveProperty(user.getId());

        // 매물 자체는 둘 다 같은 시각에 수정된 것으로 두되, 체크리스트만 그보다 더 최근으로 만든다 -
        // property.updatedAt만 보면 순서를 못 가리는 상황을 의도적으로 만든 것.
        touchUpdatedAt(withRecentChecklist, LocalDateTime.of(2026, 1, 1, 0, 0));
        touchUpdatedAt(withoutChecklist, LocalDateTime.of(2026, 1, 1, 0, 0));
        touchUpdatedAt(checklist, LocalDateTime.of(2026, 6, 1, 0, 0));

        Page<Object[]> result = checklistRepository.findOverviewByUserId(
                user.getId(), PropertyStatus.ACTIVE, PageRequest.of(0, 20));

        assertThat(((Property) result.getContent().get(0)[0]).getId()).isEqualTo(withRecentChecklist.getId());
        assertThat(((Property) result.getContent().get(1)[0]).getId()).isEqualTo(withoutChecklist.getId());
    }
}
