package com.algogyeyak.auth.dto;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// PasswordPolicy가 package-private이라 같은 패키지에 둔다. null 처리는 SignupRequest/
// PasswordUpdateRequest의 @NotBlank가 담당하므로 여기서는 패턴 자체의 경계만 검증한다.
class PasswordPolicyTest {

    private boolean matches(String candidate) {
        return candidate.matches(PasswordPolicy.PATTERN);
    }

    @Test
    void acceptsLettersAndDigitsAboveMinimumLength() {
        assertTrue(matches("password1"));
    }

    @Test
    void acceptsExactlyMinimumLengthOfEight() {
        assertTrue(matches("abcdefg1")); // 7 letters + 1 digit = 8
    }

    @Test
    void rejectsOneCharacterBelowMinimumLength() {
        assertFalse(matches("abcdef1")); // 6 letters + 1 digit = 7
    }

    @Test
    void acceptsExactlyMaximumLengthOfSeventyTwo() {
        String seventyTwoChars = "a".repeat(71) + "1";
        assertTrue(matches(seventyTwoChars));
    }

    @Test
    void rejectsOneCharacterAboveMaximumLength() {
        String seventyThreeChars = "a".repeat(72) + "1";
        assertFalse(matches(seventyThreeChars));
    }

    @Test
    void rejectsWhenNoDigitPresent() {
        assertFalse(matches("abcdefgh"));
    }

    @Test
    void rejectsWhenNoLetterPresent() {
        assertFalse(matches("12345678"));
    }

    @Test
    void rejectsWhitespace() {
        assertFalse(matches("abcdefg 1"));
    }

    // BCrypt가 72바이트를 넘는 부분을 조용히 잘라버리는 문제를 피하려고 ASCII 출력 가능 문자로
    // 제한해뒀다 — 멀티바이트 문자가 여전히 거부되는지가 이 정책의 핵심 존재 이유다.
    @Test
    void rejectsMultibyteCharacters() {
        assertFalse(matches("비밀번호1234"));
    }

    @Test
    void acceptsAllowedSymbolCharacters() {
        assertTrue(matches("abcdef1!"));
    }
}
