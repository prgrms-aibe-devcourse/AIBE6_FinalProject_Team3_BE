package com.algogyeyak.users.dto;

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
    private String currentStage;
}
