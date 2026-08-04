package com.algogyeyak.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class PasswordUpdateRequest {

    // 이미 비밀번호가 설정된 계정(로컬 가입이거나 이전에 설정해둔 소셜 계정)에서 비밀번호를 바꿀
    // 때만 필수다. OAuth 전용 계정이 처음 비밀번호를 설정하는 경우엔 비교할 기존 비밀번호가 없으므로
    // 비워둘 수 있다 — 실제 필수 여부 판단은 LocalAuthService.setPassword가 상황에 따라 수행한다.
    @Schema(description = "기존 비밀번호 (이미 설정돼 있는 경우 필수, OAuth 전용 계정의 최초 설정 시엔 생략 가능)", example = "oldPassword123")
    private String currentPassword;

    @Schema(description = "새 비밀번호 (영문+숫자 포함, 8~72자, 공백 제외 ASCII 출력 가능 문자)", example = "newPassword123")
    @NotBlank(message = "새 비밀번호는 필수입니다.")
    @Pattern(regexp = PasswordPolicy.PATTERN, message = PasswordPolicy.MESSAGE)
    private String newPassword;
}
