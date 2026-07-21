package com.algogyeyak.user.dto;

import com.algogyeyak.user.enums.TransactionType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class ProfileRegisterRequest {

    @Size(min = 2, max = 20, message = "닉네임은 2~20자여야 합니다.")
    private String nickname;

    @NotBlank(message = "관심 지역은 필수입니다.")
    private String interestRegion;

    @NotNull(message = "거래 유형은 필수입니다.")
    private TransactionType transactionType;

    private String currentStage;
}
