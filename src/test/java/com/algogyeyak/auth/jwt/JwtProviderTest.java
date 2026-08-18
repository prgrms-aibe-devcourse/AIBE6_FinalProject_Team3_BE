package com.algogyeyak.auth.jwt;

import com.algogyeyak.user.enums.Role;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JwtProviderTest {

    private final JwtProvider jwtProvider =
            new JwtProvider("test-secret-key-must-be-at-least-32-bytes-long", 3600);

    @Test
    void createsAndParsesValidToken() {
        String token = jwtProvider.createAccessToken(1L, "test@example.com", Role.USER);

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

        assertThrows(JwtException.class, () -> jwtProvider.parseClaims(token));
    }

    @Test
    void rejectsExpiredToken() throws InterruptedException {
        JwtProvider shortLivedProvider =
                new JwtProvider("test-secret-key-must-be-at-least-32-bytes-long", 0);
        String token = shortLivedProvider.createAccessToken(1L, "test@example.com", Role.USER);

        Thread.sleep(1100);

        assertThrows(JwtException.class, () -> jwtProvider.parseClaims(token));
    }

    @Test
    void rejectsGarbageToken() {
        assertThrows(JwtException.class, () -> jwtProvider.parseClaims("not-a-real-jwt"));
    }
}
