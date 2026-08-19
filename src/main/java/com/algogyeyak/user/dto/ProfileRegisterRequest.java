package com.algogyeyak.user.dto;

import com.algogyeyak.user.enums.CurrentStage;
import com.algogyeyak.user.enums.TransactionType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class ProfileRegisterRequest {

    // 법정동 "시도 시군구 읍면동" 조합 기준 실측 최댓값은 20자(frontend regions_nested.ts,
    // 2026-08-14 확인) - 행정구역 개편으로 이름이 길어져도 깨지지 않도록 여유를 둔다.
    @NotBlank(message = "관심 지역은 필수입니다.")
    @Size(max = 30, message = "관심 지역은 30자를 넘을 수 없습니다.")
    private String interestRegion;

    @NotNull(message = "거래 유형은 필수입니다.")
    private TransactionType transactionType;

    private CurrentStage currentStage;
}
