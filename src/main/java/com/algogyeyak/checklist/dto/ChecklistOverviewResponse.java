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
        LocalDateTime lastCheckedAt,
        Integer progressPercent,     // 체크리스트를 아직 시작 안 했으면 null (0%와 구분)
        Integer cautionCount,        // 위와 동일한 이유로 시작 전이면 null
        Integer generalMissingCount, // 위와 동일한 이유로 시작 전이면 null. status=COMPLETED여도 0보다 클
                                      // 수 있다 - refreshStatus()가 REQUIRED만 보고 완료를 판정하기
                                      // 때문(GENERAL은 완료 판정에서 제외됨). FE는 COMPLETED일 때 이
                                      // 값으로 "일반 항목 N개 남음" 텍스트를 보여주고 progressPercent
                                      // 바는 숨긴다("완료" 배지와 낮은 퍼센트가 함께 보이는 모순 방지).
        Integer requiredMissingCount // 위와 동일한 이유로 시작 전이면 null. status=COMPLETED면 정의상
                                      // 항상 0 - IN_PROGRESS 카드에서 "필수 항목 N개 남음"으로 쓰인다.
) {
    public static ChecklistOverviewResponse from(
            Property property, Checklist checklist,
            Integer progressPercent, Integer cautionCount,
            Integer generalMissingCount, Integer requiredMissingCount
    ) {
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
                lastCheckedAt,
                progressPercent,
                cautionCount,
                generalMissingCount,
                requiredMissingCount
        );
    }
}
