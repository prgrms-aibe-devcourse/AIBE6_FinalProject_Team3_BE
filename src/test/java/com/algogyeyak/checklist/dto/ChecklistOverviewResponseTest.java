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

        ChecklistOverviewResponse response = ChecklistOverviewResponse.from(property, checklist);

        assertThat(response.lastCheckedAt()).isEqualTo(checklistUpdatedAt);
    }

    @Test
    @DisplayName("체크리스트가 없으면(시작 전) 최종 점검일은 property.updatedAt으로 대체된다")
    void lastCheckedAtFallsBackToPropertyUpdatedAtWhenNoChecklist() {
        LocalDateTime propertyUpdatedAt = LocalDateTime.of(2026, 6, 1, 12, 0);
        Property property = property(propertyUpdatedAt);

        ChecklistOverviewResponse response = ChecklistOverviewResponse.from(property, null);

        assertThat(response.checklistId()).isNull();
        assertThat(response.status()).isEqualTo(ChecklistStatus.NOT_STARTED);
        assertThat(response.lastCheckedAt()).isEqualTo(propertyUpdatedAt);
    }
}
