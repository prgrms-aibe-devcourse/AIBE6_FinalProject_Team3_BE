package com.algogyeyak.checklist.dto;

import com.algogyeyak.checklist.entity.Checklist;
import com.algogyeyak.checklist.entity.ChecklistCategory;
import com.algogyeyak.checklist.entity.ChecklistItem;
import com.algogyeyak.checklist.entity.ChecklistStatus;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

public record ChecklistResponse(
        Long id,
        Long propertyId,
        int templateVersion,
        ChecklistStatus status,
        List<ChecklistItemResponse> items
) {
    // FE(Frontend/app/data/checklist.ts)가 서류·행정을 의도적으로 맨 마지막에 배치한다 - REQUIRED 항목이
    // 이 카테고리에 몰려 있어서, 다른 탭을 다 보기 전에 "다음 단계" 버튼이 떠버리는 걸 막기 위함이다.
    // enum 선언 순서(INDOOR,NOISE,SAFETY,DOCUMENTS,AREA)로는 이 순서를 표현할 수 없어 별도 순위 테이블을 둔다.
    private static final Map<ChecklistCategory, Integer> CATEGORY_DISPLAY_ORDER = Map.of(
            ChecklistCategory.INDOOR, 0,
            ChecklistCategory.NOISE, 1,
            ChecklistCategory.SAFETY, 2,
            ChecklistCategory.AREA, 3,
            ChecklistCategory.DOCUMENTS, 4
    );

    private static final Comparator<ChecklistItem> DISPLAY_ORDER_COMPARATOR = Comparator
            .comparingInt((ChecklistItem item) -> CATEGORY_DISPLAY_ORDER.get(item.getCategory()))
            .thenComparing(ChecklistItem::getImportance)
            .thenComparingInt(ChecklistItem::getDisplayOrder);

    public static ChecklistResponse from(Checklist checklist) {
        return new ChecklistResponse(
                checklist.getId(),
                checklist.getProperty().getId(),
                checklist.getTemplateVersion(),
                checklist.getStatus(),
                checklist.getItems().stream()
                        .sorted(DISPLAY_ORDER_COMPARATOR)
                        .map(ChecklistItemResponse::from)
                        .toList()
        );
    }
}
