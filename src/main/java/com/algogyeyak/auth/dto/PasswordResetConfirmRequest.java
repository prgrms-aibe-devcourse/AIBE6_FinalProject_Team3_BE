package com.algogyeyak.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class PasswordResetConfirmRequest {

    @Schema(description = "재설정 이메일에 담긴 토큰(쿼리 파라미터 token 값)")
    @NotBlank(message = "토큰은 필수입니다.")
    private String token;

    @Schema(description = "새 비밀번호 (영문+숫자 포함, 8~72자, 공백 제외 ASCII 출력 가능 문자)", example = "newPassword123")
    @NotBlank(message = "새 비밀번호는 필수입니다.")
    @Pattern(regexp = PasswordPolicy.PATTERN, message = PasswordPolicy.MESSAGE)
    private String newPassword;
}
