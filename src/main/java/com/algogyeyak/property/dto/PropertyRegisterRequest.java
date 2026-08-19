package com.algogyeyak.property.dto;

import com.algogyeyak.property.entity.PropertyType;
import com.algogyeyak.property.entity.TransactionType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.List;

/**
 * 매물 등록 요청. 전월세(JEONSE/MONTHLY_RENT)만 지원한다.
 * deposit은 두 거래유형 모두 필수, monthlyRent는 MONTHLY_RENT일 때만 필수
 * (거래유형별 조건부 검증이라 어노테이션이 아닌 Service에서 검증한다).
 *
 * deposit/area는 요구사항 문서상 "0 이상"이지만 의도적으로 @Positive(0 초과)를 유지한다 -
 * 면적이 0㎡인 매물은 존재할 수 없고, 보증금 0원은 JEONSE에서는 성립 자체가 안 된다(전세의
 * 정의 자체가 보증금 있는 거래). MONTHLY_RENT의 "무보증 월세"처럼 deposit=0이 의미 있는
 * 케이스가 나오면 그때 거래유형별로 분기해서 완화하는 게 맞다고 판단해 지금은 유지한다.
 */
public record PropertyRegisterRequest(
        // 선택 입력 - 이름 없는 건물도 있어 필수를 두지 않는다. 비어 있으면 PropertyService가
        // propertyType의 한글 라벨(예: "오피스텔")로 대체해서 저장한다(#222).
        String title,

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

        // 선택 입력 - 관리비 없는 매물도 있어 null 허용. 값이 있으면 0 이상이어야 함.
        @PositiveOrZero(message = "관리비는 0 이상이어야 합니다.")
        Long maintenanceFee,

        String description,

        List<PropertyImageRequest> images
) {
}
