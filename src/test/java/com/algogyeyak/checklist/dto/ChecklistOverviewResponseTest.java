package com.algogyeyak.checklist.dto;

import com.algogyeyak.checklist.entity.Checklist;
import com.algogyeyak.checklist.entity.ChecklistStatus;
import com.algogyeyak.property.entity.Property;
import com.algogyeyak.property.entity.PropertyType;
import com.algogyeyak.property.entity.TransactionType;
import com.algogyeyak.user.entity.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ChecklistOverviewResponse")
class ChecklistOverviewResponseTest {

    private Property property(LocalDateTime updatedAt) {
        Property property = Property.builder()
                .userId(1L)
                .title("테스트 매물")
                .propertyType(PropertyType.OFFICETEL)
                .transactionType(TransactionType.JEONSE)
                .deposit(10_000_000L)
                .area(20.0)
                .build();
        ReflectionTestUtils.setField(property, "id", 10L);
        ReflectionTestUtils.setField(property, "updatedAt", updatedAt);
        return property;
    }

    @Test
    @DisplayName("체크리스트가 있으면 최종 점검일은 checklist.updatedAt을 쓴다")
    void lastCheckedAtUsesChecklistUpdatedAtWhenChecklistExists() {
        Property property = property(LocalDateTime.of(2026, 1, 1, 0, 0));
        User user = User.createOAuthUser("test@example.com", "테스트유저", "http://img");
        Checklist checklist = Checklist.createFrom(user, property, 1, List.of());
        LocalDateTime checklistUpdatedAt = LocalDateTime.of(2026, 7, 30, 10, 0);
        ReflectionTestUtils.setField(checklist, "updatedAt", checklistUpdatedAt);

        ChecklistOverviewResponse response = ChecklistOverviewResponse.from(property, checklist, 75, 1, 2, 3);

        assertThat(response.lastCheckedAt()).isEqualTo(checklistUpdatedAt);
        assertThat(response.title()).isEqualTo("테스트 매물");
        assertThat(response.progressPercent()).isEqualTo(75);
        assertThat(response.cautionCount()).isEqualTo(1);
        assertThat(response.generalMissingCount()).isEqualTo(2);
        assertThat(response.requiredMissingCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("체크리스트가 없으면(시작 전) 최종 점검일은 property.updatedAt으로 대체된다")
    void lastCheckedAtFallsBackToPropertyUpdatedAtWhenNoChecklist() {
        LocalDateTime propertyUpdatedAt = LocalDateTime.of(2026, 6, 1, 12, 0);
        Property property = property(propertyUpdatedAt);

        ChecklistOverviewResponse response = ChecklistOverviewResponse.from(property, null, null, null, null, null);

        assertThat(response.checklistId()).isNull();
        assertThat(response.status()).isEqualTo(ChecklistStatus.NOT_STARTED);
        assertThat(response.lastCheckedAt()).isEqualTo(propertyUpdatedAt);
    }

    @Test
    @DisplayName("체크리스트를 시작 안 했으면 progressPercent/cautionCount는 null이다")
    void progressAndCautionAreNullWhenNotStarted() {
        Property property = property(LocalDateTime.of(2026, 6, 1, 12, 0));

        ChecklistOverviewResponse response = ChecklistOverviewResponse.from(property, null, null, null, null, null);

        assertThat(response.progressPercent()).isNull();
        assertThat(response.cautionCount()).isNull();
        assertThat(response.generalMissingCount()).isNull();
        assertThat(response.requiredMissingCount()).isNull();
    }
}
