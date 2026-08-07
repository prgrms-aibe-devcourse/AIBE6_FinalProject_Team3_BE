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

    // 형식(NicknamePolicy)/중복 검사는 여기서 무조건 하지 않고, UserService.registerProfile()이
    // "본인 기존 닉네임과 실제로 다를 때만" 수행한다 - OAuth 가입 사용자는 이 정책을 거치지 않고
    // 만들어진 기존 닉네임(카카오/구글 값 그대로)을 가질 수 있는데, 프론트가 안 바꿔도 매 요청
    // 그대로 재전송하는 이 필드에 @Pattern을 걸면 그 값을 안 바꾼 요청까지 막혀버리기 때문이다.
    private String nickname;

    @NotBlank(message = "관심 지역은 필수입니다.")
    private String interestRegion;

    @NotNull(message = "거래 유형은 필수입니다.")
    private TransactionType transactionType;

    private CurrentStage currentStage;
}
