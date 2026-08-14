package com.algogyeyak.checklist.dto;

import com.algogyeyak.checklist.entity.Checklist;
import com.algogyeyak.checklist.entity.ChecklistStatus;
import com.algogyeyak.property.entity.Property;

import java.time.LocalDateTime;

/**
 * "내 체크리스트 목록"(GET /checklists) 응답 원소 하나. 매물 하나당 정확히 1개씩 나오며,
 * 아직 체크리스트를 시작 안 한 매물은 checklistId=null, status=NOT_STARTED로 채워진다.
 */
public record ChecklistOverviewResponse(
        Long propertyId,
        Long checklistId,
        String title,
        String roadAddress,
        String jibunAddress,
        String propertyType,
        String transactionType,
        ChecklistStatus status,
        LocalDateTime lastCheckedAt
) {
    public static ChecklistOverviewResponse from(Property property, Checklist checklist) {
        var address = property.getAddress();
        // 체크리스트가 있으면 마지막으로 항목을 수정한 시각을, 아직 시작 전이면 매물 자체의
        // 마지막 수정 시각으로 대체한다 - "최종 점검일"이 항상 비어있지 않도록.
        LocalDateTime lastCheckedAt = checklist != null ? checklist.getUpdatedAt() : property.getUpdatedAt();
        return new ChecklistOverviewResponse(
                property.getId(),
                checklist != null ? checklist.getId() : null,
                property.getTitle(),
                address != null ? address.getRoadAddress() : null,
                address != null ? address.getJibunAddress() : null,
                property.getPropertyType().name(),
                property.getTransactionType().name(),
                checklist != null ? checklist.getStatus() : ChecklistStatus.NOT_STARTED,
                lastCheckedAt
        );
    }
}
