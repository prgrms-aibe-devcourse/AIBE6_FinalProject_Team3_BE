package com.algogyeyak.auth.jwt;

import com.algogyeyak.global.exception.BusinessException;
import com.algogyeyak.global.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AccessTokenRevocationServiceTest {

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
    private final AccessTokenRevocationService accessTokenRevocationService =
            new AccessTokenRevocationService(redisTemplate);

    @Test
    void revokeSetsTheJtiWithTtlUntilItsExpiry() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(30);

        accessTokenRevocationService.revoke("some-jti", expiresAt);

        verify(valueOperations).set(eq("auth:revoked-access-token:some-jti"), anyString(), any(Duration.class));
    }

    @Test
    void revokeIsNoOpWhenJtiIsNull() {
        accessTokenRevocationService.revoke(null, LocalDateTime.now().plusMinutes(30));

        verifyNoInteractions(redisTemplate);
    }

    @Test
    void revokeIsNoOpWhenAlreadyExpired() {
        accessTokenRevocationService.revoke("expired-jti", LocalDateTime.now().minusSeconds(1));

        verifyNoInteractions(redisTemplate);
    }

    @Test
    void revokeThrowsServiceUnavailableWhenRedisFails() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        doThrow(new QueryTimeoutException("redis down"))
                .when(valueOperations).set(anyString(), anyString(), any(Duration.class));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> accessTokenRevocationService.revoke("some-jti", LocalDateTime.now().plusMinutes(30)));
        assertEquals(ErrorCode.AUTH_TOKEN_STORE_UNAVAILABLE, exception.getErrorCode());
    }

    @Test
    void isRevokedReturnsTrueWhenJtiExistsInBlacklist() {
        when(redisTemplate.hasKey("auth:revoked-access-token:blacklisted-jti")).thenReturn(true);

        assertTrue(accessTokenRevocationService.isRevoked("blacklisted-jti"));
    }

    @Test
    void isRevokedReturnsFalseWhenJtiIsNotBlacklisted() {
        when(redisTemplate.hasKey(anyString())).thenReturn(false);

        assertFalse(accessTokenRevocationService.isRevoked("clean-jti"));
    }

    @Test
    void isRevokedReturnsFalseForNullJtiWithoutQueryingRedis() {
        assertFalse(accessTokenRevocationService.isRevoked(null));

        verify(redisTemplate, never()).hasKey(any());
    }

    // fail-closed 정책의 핵심 — Redis 장애 시 "블랙리스트에 없음"(false)으로 조용히 통과시키면
    // 안 된다. 예전엔 이 경우도 "무효화된 것으로 간주"(true)로 뭉뚱그렸는데, 그러면 호출부
    // (JwtAuthenticationFilter)가 진짜 블랙리스트된 토큰과 장애 상황을 구분할 수 없어 둘 다
    // 401로 응답했다 - 지금은 예외로 던져 revoke()의 장애 처리와 동일하게 503으로 구분되게 한다.
    @Test
    void isRevokedThrowsServiceUnavailableWhenRedisIsUnreachable() {
        when(redisTemplate.hasKey(anyString())).thenThrow(new QueryTimeoutException("redis down"));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> accessTokenRevocationService.isRevoked("some-jti"));
        assertEquals(ErrorCode.AUTH_TOKEN_STORE_UNAVAILABLE, exception.getErrorCode());
    }
}
