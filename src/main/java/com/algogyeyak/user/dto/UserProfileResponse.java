package com.algogyeyak.user.dto;

import com.algogyeyak.user.enums.CurrentStage;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class UserProfileResponse {
    private Long id;
    private String email;
    private String nickname;
    private String profileImageUrl;
    private String status;
    private String interestRegion;
    private String transactionType;
    private CurrentStage currentStage;
    private boolean hasPassword;
}
