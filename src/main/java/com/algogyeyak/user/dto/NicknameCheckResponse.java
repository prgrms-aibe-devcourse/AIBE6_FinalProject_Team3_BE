package com.algogyeyak.user.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class NicknameCheckResponse {
    private boolean available;
}
