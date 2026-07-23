package com.algogyeyak.property.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 매물 수정 요청. 주소/매물유형/거래유형은 등록 시 확정된 값으로 수정 대상에서 제외하고
 * (변경하려면 재등록), 가격/면적/설명만 수정 대상으로 한다.
 * deposit/area는 등록과 동일하게 항상 필수, monthlyRent는 거래유형에 따라 Service에서 검증한다.
 */
public record PropertyUpdateRequest(
        @NotNull(message = "보증금은 필수입니다.")
        @Positive(message = "보증금은 0보다 커야 합니다.")
        Long deposit,

        Long monthlyRent,

        @NotNull(message = "면적은 필수입니다.")
        @Positive(message = "면적은 0보다 커야 합니다.")
        Double area,

        String description
) {
}
