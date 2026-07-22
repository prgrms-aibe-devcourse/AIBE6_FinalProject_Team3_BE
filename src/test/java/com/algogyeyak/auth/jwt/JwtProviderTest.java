package com.algogyeyak.auth.jwt;

import com.algogyeyak.user.entity.Role;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JwtProviderTest {

    private final JwtProvider jwtProvider =
            new JwtProvider("test-secret-key-must-be-at-least-32-bytes-long", 3600);

    @Test
    void createsAndParsesValidToken() {
        String token = jwtProvider.createAccessToken(1L, "test@example.com", Role.USER);

        assertTrue(jwtProvider.validateToken(token));

        Claims claims = jwtProvider.parseClaims(token);
        assertEquals("1", claims.getSubject());
        assertEquals("test@example.com", claims.get("email", String.class));
        assertEquals("USER", claims.get("role", String.class));
    }

    @Test
    void rejectsTokenSignedWithDifferentKey() {
        JwtProvider otherProvider =
                new JwtProvider("another-secret-key-that-is-also-at-least-32-bytes", 3600);
        String token = otherProvider.createAccessToken(1L, "test@example.com", Role.USER);

        assertFalse(jwtProvider.validateToken(token));
    }

    @Test
    void rejectsExpiredToken() throws InterruptedException {
        JwtProvider shortLivedProvider =
                new JwtProvider("test-secret-key-must-be-at-least-32-bytes-long", 0);
        String token = shortLivedProvider.createAccessToken(1L, "test@example.com", Role.USER);

        Thread.sleep(1100);

        assertFalse(jwtProvider.validateToken(token));
    }

    @Test
    void rejectsGarbageToken() {
        assertFalse(jwtProvider.validateToken("not-a-real-jwt"));
    }
}
