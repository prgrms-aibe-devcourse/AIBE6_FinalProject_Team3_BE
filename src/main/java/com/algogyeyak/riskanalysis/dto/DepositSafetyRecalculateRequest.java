package com.algogyeyak.riskanalysis.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record DepositSafetyRecalculateRequest(
        @NotNull(message = "선순위보증금은 필수입니다.")
        @PositiveOrZero(message = "선순위보증금은 0 이상이어야 합니다.")
        Long seniorDeposit,

        @PositiveOrZero(message = "근저당 채권최고액은 0 이상이어야 합니다.")
        Long maxClaimAmount
) {
}
