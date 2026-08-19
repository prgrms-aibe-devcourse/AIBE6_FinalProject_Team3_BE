package com.algogyeyak.user.dto;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// null 처리는 SignupRequest/ProfileRegisterRequest/ProfileUpdateRequest의 @NotBlank(또는 선택
// 입력이면 null 자체는 통과)가 담당하므로 여기서는 패턴 자체의 경계만 검증한다.
class NicknamePolicyTest {

    private boolean matches(String candidate) {
        return candidate.matches(NicknamePolicy.PATTERN);
    }

    @Test
    void acceptsKoreanOnly() {
        assertTrue(matches("알고계약"));
    }

    @Test
    void acceptsEnglishOnly() {
        assertTrue(matches("algo"));
    }

    @Test
    void acceptsDigitsOnly() {
        assertTrue(matches("1234"));
    }

    @Test
    void acceptsMixOfKoreanEnglishAndDigits() {
        assertTrue(matches("algo계약123"));
    }

    @Test
    void acceptsExactlyMinimumLengthOfTwo() {
        assertTrue(matches("가나"));
    }

    @Test
    void rejectsOneCharacterBelowMinimumLength() {
        assertFalse(matches("가"));
    }

    @Test
    void acceptsExactlyMaximumLengthOfTwenty() {
        assertTrue(matches("가".repeat(20)));
    }

    @Test
    void rejectsOneCharacterAboveMaximumLength() {
        assertFalse(matches("가".repeat(21)));
    }

    @Test
    void rejectsSpecialCharacters() {
        assertFalse(matches("algo!"));
    }

    @Test
    void rejectsWhitespace() {
        assertFalse(matches("algo 계약"));
    }

    @Test
    void rejectsEmoji() {
        assertFalse(matches("algo😀"));
    }

    // HTML5 <input pattern="...">는 브라우저가 이미 ^(?:...)$ 로 감싸므로, GET /users/nickname-policy가
    // 내려주는 값에는 PATTERN 앞뒤의 ^/$가 없어야 한다 — 있으면 이중 앵커링된다.
    @Test
    void htmlInputPatternStripsSurroundingAnchors() {
        assertEquals("[가-힣a-zA-Z0-9]{2,20}", NicknamePolicy.HTML_INPUT_PATTERN);
        assertFalse(NicknamePolicy.HTML_INPUT_PATTERN.startsWith("^"));
        assertFalse(NicknamePolicy.HTML_INPUT_PATTERN.endsWith("$"));
    }
}
