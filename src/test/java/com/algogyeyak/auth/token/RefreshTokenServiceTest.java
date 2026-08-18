package com.algogyeyak.auth.token;

import com.algogyeyak.global.error.ErrorCode;
import com.algogyeyak.global.exception.BusinessException;
import com.algogyeyak.user.entity.User;
import com.algogyeyak.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * issue/rotate/revoke는 이제 각각 하나의 Lua script(EVAL)로 원자적으로 실행된다(동시성 안전성 및
 * rotate의 by-user 일치 확인은 {@link RefreshTokenServiceRedisIntegrationTest}가 실제 Redis로
 * 검증) - 여기서는 스크립트 내부의 key 갱신 여부가 아니라, 스크립트 호출 인자 수(issue=3개, rotate=3개,
 * revoke=1개)로 어느 스크립트가 불렸는지 구분해 그 앞뒤의 자바 레벨 분기(사용자 조회, 예외 매핑,
 * fail-closed)만 검증한다. "제시된 토큰이 현재 세션과 일치하지 않아 스크립트가 nil을 반환하는 경우"는
 * Mockito 레벨에서는 "토큰을 못 찾은 경우"와 똑같이 관측되어(둘 다 execute()가 null 반환) 구분할 수
 * 없다 - 그래서 그 구분은 통합 테스트에서만 검증한다.
 */
class RefreshTokenServiceTest {

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
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
    }

    @Test
    void issueReturnsRawTokenAndInvokesIssueScript() {
        User user = user(1L);
        doReturn(1L).when(redisTemplate).execute(any(RedisScript.class), anyList(), any(), any(), any());

        String rawToken = refreshTokenService.issue(user);

        assertEquals(43, rawToken.length()); // base64url(32바이트, 패딩 없음)
        verify(redisTemplate).execute(any(RedisScript.class), anyList(), any(), any(), any());
    }

    @Test
    void issueProducesDifferentRawTokenOnEachCall() {
        User user = user(1L);
        doReturn(1L).when(redisTemplate).execute(any(RedisScript.class), anyList(), any(), any(), any());

        String first = refreshTokenService.issue(user);
        String second = refreshTokenService.issue(user);

        assertNotEquals(first, second);
    }

    @Test
    void issueThrowsServiceUnavailableWhenRedisFails() {
        User user = user(1L);
        doThrow(new QueryTimeoutException("redis down"))
                .when(redisTemplate).execute(any(RedisScript.class), anyList(), any(), any(), any());

        BusinessException exception = assertThrows(BusinessException.class, () -> refreshTokenService.issue(user));
        assertEquals(ErrorCode.AUTH_TOKEN_STORE_UNAVAILABLE, exception.getErrorCode());
    }

    @Test
    void rotateSucceedsAndIssuesNewTokenWhenValid() {
        User user = user(1L);
        ArgumentCaptor<String> newHashCaptor = ArgumentCaptor.forClass(String.class);
        doReturn("1").when(redisTemplate).execute(any(RedisScript.class), anyList(), newHashCaptor.capture(), any(), any());
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        stubByUserPointer("1", () -> newHashCaptor.getValue());

        RefreshTokenService.RotationResult result = refreshTokenService.rotate("presented-raw-token");

        assertEquals(user, result.user());
        assertNotEquals("presented-raw-token", result.rawToken());
    }

    // rotate()가 ROTATE_SCRIPT 커밋 이후 by-user를 다시 읽어 동시 로그인(issue())에 가로채이지
    // 않았는지 재확인한다 - 여기서는 그 재확인이 불일치를 실제로 잡아내는지만 본다(Mockito로는 진짜
    // 동시성을 재현할 수 없어, 재확인 시점에 다른 값이 보이는 상황을 직접 스텁으로 흉내낸다).
    @Test
    void rotateThrowsWhenByUserPointerWasSupersededByConcurrentLoginBeforeReturn() {
        User user = user(1L);
        doReturn("1").when(redisTemplate).execute(any(RedisScript.class), anyList(), any(), any(), any());
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        stubByUserPointer("1", () -> "some-other-session-hash-from-concurrent-login");

        BusinessException exception = assertThrows(BusinessException.class,
                () -> refreshTokenService.rotate("presented-raw-token"));

        assertEquals(ErrorCode.AUTH_REFRESH_TOKEN_INVALID, exception.getErrorCode());
    }

    private void stubByUserPointer(String userId, java.util.function.Supplier<String> value) {
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("auth:refresh-token:by-user:" + userId)).thenAnswer(invocation -> value.get());
    }

    // ROTATE_SCRIPT는 이미 커밋되어 새 세션이 실제로 살아있으므로, 동시 로그인 여부를 재확인하는 GET
    // 하나가 Redis 일시 장애로 실패했다고 해서 이미 성공한 rotate 전체를 503으로 실패시키면 안 된다 -
    // 클라이언트는 이미 죽은 이전 토큰을 들고 재시도만 반복하게 된다. best-effort로 스킵하고 성공해야 한다.
    @Test
    void rotateSucceedsWhenConcurrentLoginRecheckFailsDueToTransientRedisError() {
        User user = user(1L);
        doReturn("1").when(redisTemplate).execute(any(RedisScript.class), anyList(), any(), any(), any());
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("auth:refresh-token:by-user:1")).thenThrow(new QueryTimeoutException("redis down"));

        RefreshTokenService.RotationResult result = refreshTokenService.rotate("presented-raw-token");

        assertEquals(user, result.user());
        assertNotEquals("presented-raw-token", result.rawToken());
    }

    @Test
    void rotateThrowsWhenTokenNotFound() {
        doReturn(null).when(redisTemplate).execute(any(RedisScript.class), anyList(), any(), any(), any());

        BusinessException exception = assertThrows(BusinessException.class,
                () -> refreshTokenService.rotate("unknown-token"));
        assertEquals(ErrorCode.AUTH_REFRESH_TOKEN_INVALID, exception.getErrorCode());
    }

    // Redis TTL이 만료를 자동으로 정리하므로, 자연 만료된 토큰도 "찾을 수 없음"(스크립트가 nil 반환)과
    // 똑같이 관측된다 — 더 이상 별도의 EXPIRED 분기가 없다.
    @Test
    void rotateThrowsInvalidNotExpiredWhenTokenHasNaturallyExpiredOutOfRedis() {
        doReturn(null).when(redisTemplate).execute(any(RedisScript.class), anyList(), any(), any(), any());

        BusinessException exception = assertThrows(BusinessException.class,
                () -> refreshTokenService.rotate("expired-token"));
        assertEquals(ErrorCode.AUTH_REFRESH_TOKEN_INVALID, exception.getErrorCode());
    }

    @Test
    void rotateThrowsAndCleansUpOrphanedSessionWhenUserWithdrawn() {
        User user = user(1L);
        user.withdraw();
        doReturn("1").when(redisTemplate).execute(any(RedisScript.class), anyList(), any(), any(), any());
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> refreshTokenService.rotate("token"));
        assertEquals(ErrorCode.AUTH_REFRESH_TOKEN_INVALID, exception.getErrorCode());
        assertCleansUpBothOrphanedKeysFor("1");
    }

    @Test
    void rotateThrowsAndCleansUpOrphanedSessionWhenUserNoLongerExists() {
        doReturn("1").when(redisTemplate).execute(any(RedisScript.class), anyList(), any(), any(), any());
        when(userRepository.findById(1L)).thenReturn(Optional.empty());

        BusinessException exception = assertThrows(BusinessException.class,
                () -> refreshTokenService.rotate("token"));
        assertEquals(ErrorCode.AUTH_REFRESH_TOKEN_INVALID, exception.getErrorCode());
        assertCleansUpBothOrphanedKeysFor("1");
    }

    // ROTATE_SCRIPT가 이미 새 by-hash/by-user를 써버린 뒤 사용자 상태 확인에서 거부되는 경우,
    // by-user만 지우면 새로 만든 by-hash가 TTL까지 고아로 남는다 - 정리 스크립트가 두 키 모두를
    // 대상으로 실행되는지 검증한다(조건부 삭제 자체의 동시성 안전성은 통합 테스트에서 검증).
    @SuppressWarnings("unchecked")
    private void assertCleansUpBothOrphanedKeysFor(String userId) {
        ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass(List.class);
        verify(redisTemplate).execute(any(RedisScript.class), keysCaptor.capture(), anyString());
        List<String> keys = keysCaptor.getValue();
        assertTrue(keys.contains("auth:refresh-token:by-user:" + userId));
        assertTrue(keys.stream().anyMatch(key -> key.startsWith("auth:refresh-token:by-hash:")));
    }

    @Test
    void rotateThrowsServiceUnavailableWhenRedisFails() {
        doThrow(new QueryTimeoutException("redis down"))
                .when(redisTemplate).execute(any(RedisScript.class), anyList(), any(), any(), any());

        BusinessException exception = assertThrows(BusinessException.class,
                () -> refreshTokenService.rotate("some-token"));
        assertEquals(ErrorCode.AUTH_TOKEN_STORE_UNAVAILABLE, exception.getErrorCode());
    }

    @Test
    void revokeInvokesRevokeScriptWithThePresentedTokensHash() {
        doReturn("1").when(redisTemplate).execute(any(RedisScript.class), anyList(), anyString());

        refreshTokenService.revoke("some-token");

        verify(redisTemplate).execute(any(RedisScript.class), anyList(), anyString());
        verify(redisTemplate, never()).delete(anyString());
    }

    @Test
    void revokeIsNoOpWhenTokenUnknown() {
        doReturn(null).when(redisTemplate).execute(any(RedisScript.class), anyList(), anyString());

        refreshTokenService.revoke("unknown-token");

        verify(redisTemplate, never()).delete(anyString());
    }

    @Test
    void revokeThrowsServiceUnavailableWhenRedisFails() {
        doThrow(new QueryTimeoutException("redis down"))
                .when(redisTemplate).execute(any(RedisScript.class), anyList(), anyString());

        BusinessException exception = assertThrows(BusinessException.class,
                () -> refreshTokenService.revoke("some-token"));
        assertEquals(ErrorCode.AUTH_TOKEN_STORE_UNAVAILABLE, exception.getErrorCode());
    }

    @Test
    void revokeAllForUserDeletesBothKeysWhenSessionExists() {
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("auth:refresh-token:by-user:1")).thenReturn("current-hash");

        refreshTokenService.revokeAllForUser(1L);

        verify(redisTemplate).delete(java.util.List.of(
                "auth:refresh-token:by-user:1", "auth:refresh-token:by-hash:current-hash"));
    }

    @Test
    void revokeAllForUserIsNoOpWhenNoActiveSession() {
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("auth:refresh-token:by-user:1")).thenReturn(null);

        refreshTokenService.revokeAllForUser(1L);

        verify(redisTemplate, never()).delete(anyList());
    }

    @Test
    void revokeAllForUserThrowsServiceUnavailableWhenRedisFails() {
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("auth:refresh-token:by-user:1")).thenThrow(new QueryTimeoutException("redis down"));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> refreshTokenService.revokeAllForUser(1L));
        assertEquals(ErrorCode.AUTH_TOKEN_STORE_UNAVAILABLE, exception.getErrorCode());
    }
}
