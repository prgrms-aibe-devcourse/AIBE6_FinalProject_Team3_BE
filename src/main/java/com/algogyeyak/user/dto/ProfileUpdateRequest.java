package com.algogyeyak.users.dto;

import com.algogyeyak.users.enums.TransactionType;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class ProfileUpdateRequest {

    @Size(min = 2, max = 20, message = "닉네임은 2~20자여야 합니다.")
    private String nickname;

    private String profileImageUrl;

    private String interestRegion;

    private TransactionType transactionType;

    private String currentStage;
}
