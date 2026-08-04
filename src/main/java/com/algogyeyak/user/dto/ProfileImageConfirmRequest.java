package com.algogyeyak.user.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class ProfileImageConfirmRequest {

    @NotBlank
    private String key;
}
