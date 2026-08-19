package com.algogyeyak.property.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.List;

/**
 * 매물 수정 요청. 주소/매물유형/거래유형은 등록 시 확정된 값으로 수정 대상에서 제외하고
 * (변경하려면 재등록), 이름/가격/면적/설명/이미지만 수정 대상으로 한다.
 * title은 등록과 동일하게 선택 입력(#222), deposit/area는 항상 필수, monthlyRent는 거래유형에
 * 따라 Service에서 검증한다.
 * images는 null이면 "이미지 변경 없음"(기존 이미지 그대로 유지), 값이 있으면(빈 리스트 포함) 기존
 * 이미지를 전부 지우고 통째로 교체한다 - 부분 추가/삭제가 아니라 항상 전체 목록을 새로 제출해야 한다.
 */
public record PropertyUpdateRequest(
        // 선택 입력 - 등록 때와 동일하게 필수를 두지 않는다. 비어 있으면 PropertyService가
        // propertyType의 한글 라벨로 대체해서 저장한다(#222).
        String title,

        @NotNull(message = "보증금은 필수입니다.")
        @Positive(message = "보증금은 0보다 커야 합니다.")
        Long deposit,

        Long monthlyRent,

        @NotNull(message = "면적은 필수입니다.")
        @Positive(message = "면적은 0보다 커야 합니다.")
        Double area,

        // 선택 입력 - 관리비 없는 매물도 있어 null 허용. 값이 있으면 0 이상이어야 함.
        @PositiveOrZero(message = "관리비는 0 이상이어야 합니다.")
        Long maintenanceFee,

        String description,

        List<PropertyImageRequest> images
) {
}
