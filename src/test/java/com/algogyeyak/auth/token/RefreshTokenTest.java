package com.algogyeyak.auth.token;

import com.algogyeyak.user.entity.User;
import com.algogyeyak.user.enums.AuthProvider;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RefreshTokenTest {

    private RefreshToken tokenExpiringAt(LocalDateTime expiresAt) {
        User user = User.createOAuthUser("test@example.com", "테스트유저", "http://img", AuthProvider.KAKAO, "123");
        return RefreshToken.builder().user(user).tokenHash("hash").expiresAt(expiresAt).build();
    }

    @Test
    void isNotExpiredBeforeExpiryInstant() {
        LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(1);
        RefreshToken token = tokenExpiringAt(expiresAt);

        assertFalse(token.isExpired(expiresAt.minusSeconds(1)));
    }

    @Test
    void isExpiredExactlyAtExpiryInstant() {
        LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(1);
        RefreshToken token = tokenExpiringAt(expiresAt);

        // 만료 시각 자체는 이미 만료된 것으로 취급해야 한다 (경계값 포함).
        assertTrue(token.isExpired(expiresAt));
    }

    @Test
    void isExpiredAfterExpiryInstant() {
        LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(1);
        RefreshToken token = tokenExpiringAt(expiresAt);

        assertTrue(token.isExpired(expiresAt.plusSeconds(1)));
    }
}
