package com.algogyeyak.auth.dto;

import com.algogyeyak.user.dto.NicknamePolicy;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class SignupRequest {

    @Schema(description = "이메일", example = "user@example.com")
    @NotBlank(message = "이메일은 필수입니다.")
    @Email(message = "이메일 형식이 올바르지 않습니다.")
    private String email;

    @Schema(description = "비밀번호 (영문+숫자 포함, 8~72자, 공백 제외 ASCII 출력 가능 문자)", example = "password123")
    @NotBlank(message = "비밀번호는 필수입니다.")
    @Pattern(regexp = PasswordPolicy.PATTERN, message = PasswordPolicy.MESSAGE)
    private String password;

    @Schema(description = "닉네임 (한글/영문/숫자, 2~20자)", example = "algo")
    @NotBlank(message = "닉네임은 필수입니다.")
    @Pattern(regexp = NicknamePolicy.PATTERN, message = NicknamePolicy.MESSAGE)
    private String nickname;
}
