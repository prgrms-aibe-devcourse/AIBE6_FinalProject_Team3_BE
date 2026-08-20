package com.algogyeyak.auth.jwt;

import com.algogyeyak.user.enums.Role;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

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

        awaitTokenExpiry(token);

        assertThrows(JwtException.class, () -> jwtProvider.parseClaims(token));
    }

    @Test
    void rejectsGarbageToken() {
        assertThrows(JwtException.class, () -> jwtProvider.parseClaims("not-a-real-jwt"));
    }

    // 고정 sleep(1100ms)은 느린 CI/컨테이너 등에서 실제 만료보다 먼저 깨어날 수 있어 드물게
    // 흔들린다 - RefreshTokenServiceRedisIntegrationTest.awaitKeyAbsence()와 동일하게, "얼마나
    // 기다릴지" 추측하는 대신 토큰이 실제로 만료됐는지(파싱이 실제로 실패하는지)를 짧은 간격으로
    // 직접 확인(polling)한다.
    private void awaitTokenExpiry(String token) throws InterruptedException {
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(10);
        while (System.currentTimeMillis() < deadline) {
            try {
                jwtProvider.parseClaims(token);
            } catch (JwtException e) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("토큰이 TTL로 자연 만료되지 않았다: " + token);
    }
}
