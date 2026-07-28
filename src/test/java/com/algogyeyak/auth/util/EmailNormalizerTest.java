package com.algogyeyak.auth.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class EmailNormalizerTest {

    @Test
    void trimsSurroundingWhitespace() {
        assertEquals("test@example.com", EmailNormalizer.normalize("  test@example.com  "));
    }

    @Test
    void lowercasesUpperCaseLetters() {
        assertEquals("test@example.com", EmailNormalizer.normalize("Test@EXAMPLE.com"));
    }

    @Test
    void trimsAndLowercasesTogether() {
        assertEquals("test@example.com", EmailNormalizer.normalize("  Test@Example.COM  "));
    }

    @Test
    void leavesAlreadyNormalizedEmailUnchanged() {
        assertEquals("test@example.com", EmailNormalizer.normalize("test@example.com"));
    }

    @Test
    void returnsNullForNullInput() {
        assertNull(EmailNormalizer.normalize(null));
    }

    // 로컬 가입과 OAuth 연동이 서로 다른 대소문자로 저장하면 계정 자동 연동이 조용히 깨진다는 게
    // 이 유틸의 존재 이유이므로, 대소문자만 다른 두 입력이 실제로 같은 값으로 수렴하는지 직접 확인한다.
    @Test
    void caseVariantsOfTheSameEmailNormalizeToTheSameValue() {
        String local = EmailNormalizer.normalize("User@Example.com");
        String oauth = EmailNormalizer.normalize("user@EXAMPLE.COM");

        assertEquals(local, oauth);
    }
}
