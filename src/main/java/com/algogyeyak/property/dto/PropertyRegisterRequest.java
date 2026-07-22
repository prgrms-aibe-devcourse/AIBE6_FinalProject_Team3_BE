package com.algogyeyak.property.dto;

import com.algogyeyak.property.entity.PropertyType;
import com.algogyeyak.property.entity.TransactionType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.List;

/**
 * 매물 등록 요청. 전월세(JEONSE/MONTHLY_RENT)만 지원한다.
 * deposit은 두 거래유형 모두 필수, monthlyRent는 MONTHLY_RENT일 때만 필수
 * (거래유형별 조건부 검증이라 어노테이션이 아닌 Service에서 검증한다).
 */
public record PropertyRegisterRequest(
        @NotBlank(message = "주소는 필수입니다.")
        String address,

        @NotNull(message = "매물 유형은 필수입니다.")
        PropertyType propertyType,

        @NotNull(message = "거래 유형은 필수입니다.")
        TransactionType transactionType,

        @NotNull(message = "보증금은 필수입니다.")
        @Positive(message = "보증금은 0보다 커야 합니다.")
        Long deposit,

        Long monthlyRent,

        @NotNull(message = "면적은 필수입니다.")
        @Positive(message = "면적은 0보다 커야 합니다.")
        Double area,

        String description,

        List<String> imageUrls
) {
}
