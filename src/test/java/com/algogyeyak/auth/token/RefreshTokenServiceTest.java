package com.algogyeyak.auth.token;

import com.algogyeyak.global.error.ErrorCode;
import com.algogyeyak.global.exception.BusinessException;
import com.algogyeyak.user.entity.User;
import com.algogyeyak.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RefreshTokenServiceTest {

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final RefreshTokenService refreshTokenService =
            new RefreshTokenService(redisTemplate, userRepository);

    private User user(Long id) {
        User user = User.createOAuthUser("test@example.com", "테스트유저", "http://img");
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(refreshTokenService, "refreshTokenValiditySeconds", 1209600L);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    void issueStoresBothForwardAndReverseKeysWithTtl() {
        User user = user(1L);
        when(valueOperations.get("auth:refresh-token:by-user:1")).thenReturn(null);

        String rawToken = refreshTokenService.issue(user);

        assertEquals(43, rawToken.length()); // base64url(32바이트, 패딩 없음)
        verify(valueOperations).set(eq("auth:refresh-token:by-user:1"), anyString(), any(Duration.class));
        verify(valueOperations, never()).getAndDelete(anyString());
    }

    @Test
    void issueInvalidatesThePreviousSessionsByHashEntry() {
        User user = user(1L);
        when(valueOperations.get("auth:refresh-token:by-user:1")).thenReturn("old-hash");

        refreshTokenService.issue(user);

        verify(redisTemplate).delete("auth:refresh-token:by-hash:old-hash");
    }

    @Test
    void issueProducesDifferentRawTokenOnEachCall() {
        User user = user(1L);

        String first = refreshTokenService.issue(user);
        String second = refreshTokenService.issue(user);

        assertNotEquals(first, second);
    }

    @Test
    void issueThrowsServiceUnavailableWhenRedisFails() {
        User user = user(1L);
        when(valueOperations.get(anyString())).thenThrow(new QueryTimeoutException("redis down"));

        BusinessException exception = assertThrows(BusinessException.class, () -> refreshTokenService.issue(user));
        assertEquals(ErrorCode.AUTH_TOKEN_STORE_UNAVAILABLE, exception.getErrorCode());
    }

    @Test
    void rotateSucceedsAndIssuesNewTokenWhenValid() {
        User user = user(1L);
        when(valueOperations.getAndDelete("auth:refresh-token:by-hash:" + hash("presented-raw-token"))).thenReturn("1");
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        RefreshTokenService.RotationResult result = refreshTokenService.rotate("presented-raw-token");

        assertEquals(user, result.user());
        assertNotEquals("presented-raw-token", result.rawToken());
        verify(valueOperations).set(eq("auth:refresh-token:by-user:1"), anyString(), any(Duration.class));
    }

    @Test
    void rotateThrowsWhenTokenNotFound() {
        when(valueOperations.getAndDelete(anyString())).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> refreshTokenService.rotate("unknown-token"));
        assertEquals(ErrorCode.AUTH_REFRESH_TOKEN_INVALID, exception.getErrorCode());
    }

    // Redis TTL이 만료를 자동으로 정리하므로, 자연 만료된 토큰도 "찾을 수 없음"(getAndDelete가 null
    // 반환)과 똑같이 관측된다 — 더 이상 별도의 EXPIRED 분기가 없다.
    @Test
    void rotateThrowsInvalidNotExpiredWhenTokenHasNaturallyExpiredOutOfRedis() {
        when(valueOperations.getAndDelete(anyString())).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> refreshTokenService.rotate("expired-token"));
        assertEquals(ErrorCode.AUTH_REFRESH_TOKEN_INVALID, exception.getErrorCode());
    }

    @Test
    void rotateThrowsAndCleansUpReverseIndexWhenUserWithdrawn() {
        User user = user(1L);
        user.withdraw();
        when(valueOperations.getAndDelete(anyString())).thenReturn("1");
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> refreshTokenService.rotate("token"));
        assertEquals(ErrorCode.AUTH_REFRESH_TOKEN_INVALID, exception.getErrorCode());
        verify(redisTemplate).delete("auth:refresh-token:by-user:1");
    }

    @Test
    void rotateThrowsAndCleansUpReverseIndexWhenUserNoLongerExists() {
        when(valueOperations.getAndDelete(anyString())).thenReturn("1");
        when(userRepository.findById(1L)).thenReturn(Optional.empty());

        BusinessException exception = assertThrows(BusinessException.class,
                () -> refreshTokenService.rotate("token"));
        assertEquals(ErrorCode.AUTH_REFRESH_TOKEN_INVALID, exception.getErrorCode());
        verify(redisTemplate).delete("auth:refresh-token:by-user:1");
    }

    @Test
    void rotateThrowsServiceUnavailableWhenRedisFails() {
        when(valueOperations.getAndDelete(anyString())).thenThrow(new QueryTimeoutException("redis down"));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> refreshTokenService.rotate("some-token"));
        assertEquals(ErrorCode.AUTH_TOKEN_STORE_UNAVAILABLE, exception.getErrorCode());
    }

    @Test
    void revokeDeletesBothKeysWhenTokenIsKnown() {
        when(valueOperations.getAndDelete("auth:refresh-token:by-hash:" + hash("some-token"))).thenReturn("1");

        refreshTokenService.revoke("some-token");

        verify(redisTemplate).delete("auth:refresh-token:by-user:1");
    }

    @Test
    void revokeIsNoOpWhenTokenUnknown() {
        when(valueOperations.getAndDelete(anyString())).thenReturn(null);

        refreshTokenService.revoke("unknown-token");

        verify(redisTemplate, never()).delete(anyString());
    }

    @Test
    void revokeThrowsServiceUnavailableWhenRedisFails() {
        when(valueOperations.getAndDelete(anyString())).thenThrow(new QueryTimeoutException("redis down"));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> refreshTokenService.revoke("some-token"));
        assertEquals(ErrorCode.AUTH_TOKEN_STORE_UNAVAILABLE, exception.getErrorCode());
    }

    private static String hash(String rawToken) {
        try {
            byte[] hashed = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(rawToken.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(hashed);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
