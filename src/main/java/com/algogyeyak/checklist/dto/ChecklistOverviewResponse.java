package com.algogyeyak.checklist.dto;

import com.algogyeyak.checklist.entity.Checklist;
import com.algogyeyak.checklist.entity.ChecklistStatus;
import com.algogyeyak.property.entity.Property;

/**
 * "내 체크리스트 목록"(GET /checklists) 응답 원소 하나. 매물 하나당 정확히 1개씩 나오며,
 * 아직 체크리스트를 시작 안 한 매물은 checklistId=null, status=NOT_STARTED로 채워진다.
 */
public record ChecklistOverviewResponse(
        Long propertyId,
        Long checklistId,
        String roadAddress,
        String jibunAddress,
        String propertyType,
        String transactionType,
        ChecklistStatus status
) {
    public static ChecklistOverviewResponse from(Property property, Checklist checklist) {
        var address = property.getAddress();
        return new ChecklistOverviewResponse(
                property.getId(),
                checklist != null ? checklist.getId() : null,
                address != null ? address.getRoadAddress() : null,
                address != null ? address.getJibunAddress() : null,
                property.getPropertyType().name(),
                property.getTransactionType().name(),
                checklist != null ? checklist.getStatus() : ChecklistStatus.NOT_STARTED
        );
    }
}
