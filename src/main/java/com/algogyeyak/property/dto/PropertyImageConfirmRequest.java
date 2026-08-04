package com.algogyeyak.property.dto;

import jakarta.validation.constraints.NotBlank;

public record PropertyImageConfirmRequest(
        @NotBlank(message = "key는 필수입니다.")
        String key
) {
}
