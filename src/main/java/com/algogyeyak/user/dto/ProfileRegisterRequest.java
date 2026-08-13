package com.algogyeyak.user.dto;

import com.algogyeyak.user.enums.CurrentStage;
import com.algogyeyak.user.enums.TransactionType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class ProfileRegisterRequest {

    @NotBlank(message = "관심 지역은 필수입니다.")
    private String interestRegion;

    @NotNull(message = "거래 유형은 필수입니다.")
    private TransactionType transactionType;

    private CurrentStage currentStage;
}
