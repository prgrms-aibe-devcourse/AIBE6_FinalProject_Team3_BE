package com.algogyeyak.auth.service;

import com.algogyeyak.auth.token.RefreshTokenService;
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
import org.springframework.mail.MailSendException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PasswordResetServiceTest {

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> valueOps = mock(ValueOperations.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final EmailService emailService = mock(EmailService.class);
    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
    private final RefreshTokenService refreshTokenService = mock(RefreshTokenService.class);
    private final PasswordResetService service = new PasswordResetService(
            redisTemplate, userRepository, emailService, passwordEncoder, refreshTokenService);

    private User localUser(Long id) {
        User user = User.createLocalUser("test@example.com", "encoded-hash", "테스트유저");
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    // PasswordResetService.hash()와 동일한 알고리즘 - 서비스 코드를 바꾸지 않고, ISSUE_SCRIPT에
    // 실제로 넘어간 tokenHash 인자가 이메일로 발송된 raw token의 해시와 일치하는지 검증하기 위해
    // 테스트에서도 같은 해시를 계산한다(RefreshTokenServiceRedisIntegrationTest.hash()와 동일한 패턴).
    private static String hash(String rawToken) throws Exception {
        byte[] hashed = MessageDigest.getInstance("SHA-256").digest(rawToken.getBytes(StandardCharsets.UTF_8));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hashed);
    }

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        ReflectionTestUtils.setField(service, "tokenValiditySeconds", 1800L);
        ReflectionTestUtils.setField(service, "requestCooldownSeconds", 60L);
        ReflectionTestUtils.setField(service, "frontendBaseUrl", "http://localhost:3000");
        // EmailService.sendPasswordResetLink()는 이제 @Async라 CompletableFuture<Void>를
        // 반환한다(이 목은 Spring 프록시 없이 직접 호출되므로 항상 이미 완료된 future를 반환하게
        // 스텁한다) - 개별 테스트가 실패 시나리오를 검증할 때만 이 기본값을 덮어쓴다.
        when(emailService.sendPasswordResetLink(anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
    }

    @Test
    void requestResetThrowsWhenCooldownActive() {
        when(valueOps.setIfAbsent(eq("auth:password-reset:cooldown:test@example.com"), anyString(), any(Duration.class)))
                .thenReturn(false);

        BusinessException exception = assertThrows(BusinessException.class, () -> service.requestReset("test@example.com"));

        assertEquals(ErrorCode.AUTH_PASSWORD_RESET_TOO_MANY_REQUESTS, exception.getErrorCode());
        verify(emailService, never()).sendPasswordResetLink(anyString(), anyString());
    }

    // 아래 requestResetSwallowsRedisIssueFailureToAvoidRevealingAccountExists()(토큰 발급 단계의
    // Redis 장애)와 정반대 동작이다 - 그쪽은 이미 "이 이메일에 재설정 가능한 계정이 있다"는 사실이
    // 확정된 뒤라 실패를 삼켜야 하지만, 여기 쿨다운 확인은 계정 존재 여부와 무관하게 항상 거치는
    // 단계라 실패를 삼키면 안 된다 - 그대로 AUTH_TOKEN_STORE_UNAVAILABLE로 전파돼야 한다.
    @Test
    void requestResetThrowsServiceUnavailableWhenCooldownCheckRedisFails() {
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenThrow(new QueryTimeoutException("redis down"));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.requestReset("test@example.com"));

        assertEquals(ErrorCode.AUTH_TOKEN_STORE_UNAVAILABLE, exception.getErrorCode());
        verify(emailService, never()).sendPasswordResetLink(anyString(), anyString());
    }

    @Test
    void requestResetDoesNothingWhenAccountNotFound() {
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.empty());

        service.requestReset("test@example.com");

        verify(emailService, never()).sendPasswordResetLink(anyString(), anyString());
        verify(redisTemplate, never()).execute(any(RedisScript.class), anyList(), any(), any(), any());
    }

    @Test
    void requestResetDoesNothingForSocialOnlyAccount() {
        User socialUser = User.createOAuthUser("test@example.com", "소셜유저", null);
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(socialUser));

        service.requestReset("test@example.com");

        verify(emailService, never()).sendPasswordResetLink(anyString(), anyString());
    }

    @Test
    void requestResetDoesNothingForWithdrawnAccount() {
        User user = localUser(1L);
        user.withdraw();
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        when(userRepository.findByEmail(anyString())).thenReturn(Optional.of(user));

        service.requestReset("test@example.com");

        verify(emailService, never()).sendPasswordResetLink(anyString(), anyString());
    }

    @Test
    void requestResetIssuesTokenAndSendsEmailForEligibleAccount() throws Exception {
        User user = localUser(7L);
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(user));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<Object> tokenHashCaptor = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<Object> ttlCaptor = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<Object> userIdCaptor = ArgumentCaptor.forClass(Object.class);
        doReturn(1L).when(redisTemplate).execute(any(RedisScript.class), keysCaptor.capture(),
                tokenHashCaptor.capture(), ttlCaptor.capture(), userIdCaptor.capture());

        service.requestReset("test@example.com");

        ArgumentCaptor<String> linkCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendPasswordResetLink(eq("test@example.com"), linkCaptor.capture());
        String resetLink = linkCaptor.getValue();
        assertEquals(true, resetLink.startsWith("http://localhost:3000/reset-password?token="));

        // RefreshTokenServiceTest.rotateSucceedsAndIssuesNewTokenWhenValid()처럼 loose any()로
        // 스텁만 하고 끝내지 않고, ISSUE_SCRIPT에 실제로 넘어간 key/args가 이 요청의 userId/토큰
        // 해시/TTL과 정확히 일치하는지 검증한다.
        assertEquals(List.of("auth:password-reset:by-user:7"), keysCaptor.getValue());
        assertEquals("1800", ttlCaptor.getValue());
        assertEquals("7", userIdCaptor.getValue());
        String rawToken = resetLink.substring(resetLink.indexOf("token=") + "token=".length());
        assertEquals(hash(rawToken), tokenHashCaptor.getValue());
    }

    @Test
    void requestResetSwallowsMailFailureToAvoidRevealingAccountExists() {
        // 계정이 존재하는 분기(여기)에서만 발생할 수 있는 실패를 그대로 노출하면(502 등),
        // 존재하지 않는 이메일/소셜 전용 계정(둘 다 항상 200)과 응답이 갈려 계정 존재 여부가
        // 새어나간다 - 메일 발송 실패도 로그만 남기고 예외 없이 정상 종료해야 한다.
        User user = localUser(1L);
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(user));
        doReturn(1L).when(redisTemplate).execute(any(RedisScript.class), anyList(), any(), any(), any());
        when(emailService.sendPasswordResetLink(anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new MailSendException("smtp down")));

        service.requestReset("test@example.com");

        // 회귀 테스트 - 메일 발송이 서버 쪽 이유로 실패했는데 쿨다운만 남으면, 사용자가 링크를
        // 받지도 못한 채 60초를 그냥 기다려야 한다(응답 자체는 계정 존재 여부 비노출을 위해 여전히
        // 성공으로 유지되지만, 쿨다운은 풀어 즉시 재시도를 허용해야 한다).
        verify(redisTemplate).execute(any(RedisScript.class), eq(List.of("auth:password-reset:cooldown:test@example.com")), anyString());
    }

    // 회귀 테스트 - emailTaskExecutor의 큐가 가득 차면 @Async 프록시가 Future를 반환하기도 전에
    // TaskRejectedException을 동기로 던진다. 이 지점(존재하는 활성 로컬 계정 분기)에서만 발생할 수
    // 있는 예외를 그대로 노출하면 존재하지 않는 이메일/소셜 전용 계정과 응답이 갈려 계정 존재
    // 여부가 새어나간다 - 위 mailFailure 테스트(실패한 Future)와 마찬가지로 예외 없이 정상 종료돼야
    // 한다.
    @Test
    void requestResetSwallowsEmailTaskRejectionToAvoidRevealingAccountExists() {
        User user = localUser(1L);
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(user));
        doReturn(1L).when(redisTemplate).execute(any(RedisScript.class), anyList(), any(), any(), any());
        when(emailService.sendPasswordResetLink(anyString(), anyString()))
                .thenThrow(new org.springframework.core.task.TaskRejectedException("queue full"));

        service.requestReset("test@example.com");

        verify(redisTemplate).execute(any(RedisScript.class), eq(List.of("auth:password-reset:cooldown:test@example.com")), anyString());
    }

    @Test
    void requestResetSwallowsRedisIssueFailureToAvoidRevealingAccountExists() {
        // 위와 동일한 이유 - 토큰 발급(Redis) 자체가 실패해도 503을 노출하지 않는다.
        User user = localUser(1L);
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(user));
        doThrow(new QueryTimeoutException("redis down"))
                .when(redisTemplate).execute(any(RedisScript.class), anyList(), any(), any(), any());

        service.requestReset("test@example.com");

        verify(emailService, never()).sendPasswordResetLink(anyString(), anyString());
    }

    @Test
    void confirmResetThrowsWhenTokenNotFound() {
        doReturn(null).when(redisTemplate).execute(any(RedisScript.class), anyList(), anyString());

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.confirmReset("some-token", "newPassword1"));

        assertEquals(ErrorCode.AUTH_PASSWORD_RESET_TOKEN_INVALID, exception.getErrorCode());
    }

    @Test
    void confirmResetUpdatesPasswordAndRevokesSessionsOnSuccess() {
        User user = localUser(1L);
        doReturn("1").when(redisTemplate).execute(any(RedisScript.class), anyList(), anyString());
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(passwordEncoder.encode("newPassword1")).thenReturn("new-encoded-hash");

        service.confirmReset("some-token", "newPassword1");

        assertEquals("new-encoded-hash", user.getPasswordHash());
        assertNotNull(user.getPasswordChangedAt());
        verify(refreshTokenService).revokeAllForUser(1L);
    }

    // 회귀 테스트 - 재설정 토큰은 CONSUME_SCRIPT로 이미 돌이킬 수 없이 소각된 뒤라, 그 다음 단계인
    // revokeAllForUser()가 Redis 장애로 실패해도 비밀번호 변경 자체가 롤백되면 안 된다(토큰은 이미
    // 없어졌는데 비밀번호도 안 바뀌면 사용자는 완전히 새 이메일을 다시 받아야 한다). 세션 정리는
    // best-effort여야 하고, confirmReset()은 예외 없이 정상 종료해야 한다.
    @Test
    void confirmResetSucceedsEvenWhenSessionRevocationFails() {
        User user = localUser(1L);
        doReturn("1").when(redisTemplate).execute(any(RedisScript.class), anyList(), anyString());
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(passwordEncoder.encode("newPassword1")).thenReturn("new-encoded-hash");
        doThrow(new BusinessException(ErrorCode.AUTH_TOKEN_STORE_UNAVAILABLE))
                .when(refreshTokenService).revokeAllForUser(1L);

        service.confirmReset("some-token", "newPassword1");

        assertEquals("new-encoded-hash", user.getPasswordHash());
    }

    @Test
    void confirmResetThrowsWhenUserWithdrawn() {
        User user = localUser(1L);
        user.withdraw();
        doReturn("1").when(redisTemplate).execute(any(RedisScript.class), anyList(), anyString());
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.confirmReset("some-token", "newPassword1"));

        assertEquals(ErrorCode.AUTH_PASSWORD_RESET_TOKEN_INVALID, exception.getErrorCode());
    }

    // confirmResetThrowsWhenUserWithdrawn()의 형제 케이스 - PasswordResetService.confirmReset()의
    // 자격 필터(!isWithdrawn() && !isSuspended() && passwordHash != null) 중 withdrawn 분기만
    // 테스트가 있었다. 정지된 계정도 동일하게 거부되어야 한다.
    @Test
    void confirmResetThrowsWhenUserSuspended() {
        User user = localUser(1L);
        user.suspend();
        doReturn("1").when(redisTemplate).execute(any(RedisScript.class), anyList(), anyString());
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.confirmReset("some-token", "newPassword1"));

        assertEquals(ErrorCode.AUTH_PASSWORD_RESET_TOKEN_INVALID, exception.getErrorCode());
    }

    // 소셜 전용 계정으로 바뀐 경우(passwordHash == null) - 재설정 토큰이 발급된 뒤 그 사이 계정이
    // 소셜 전용으로 바뀌었거나 애초에 소셜 전용 계정이었던 경우를 막는다(requestReset() 자체는
    // passwordHash가 있는 계정에만 토큰을 발급하지만, confirmReset()도 동일한 필터를 독립적으로
    // 다시 확인해야 한다).
    @Test
    void confirmResetThrowsWhenPasswordHashIsNullForSocialOnlyAccount() {
        User user = User.createOAuthUser("test@example.com", "소셜유저", null);
        ReflectionTestUtils.setField(user, "id", 1L);
        doReturn("1").when(redisTemplate).execute(any(RedisScript.class), anyList(), anyString());
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.confirmReset("some-token", "newPassword1"));

        assertEquals(ErrorCode.AUTH_PASSWORD_RESET_TOKEN_INVALID, exception.getErrorCode());
    }

    @Test
    void confirmResetThrowsServiceUnavailableWhenRedisFails() {
        doThrow(new QueryTimeoutException("redis down"))
                .when(redisTemplate).execute(any(RedisScript.class), anyList(), anyString());

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.confirmReset("some-token", "newPassword1"));

        assertEquals(ErrorCode.AUTH_TOKEN_STORE_UNAVAILABLE, exception.getErrorCode());
    }
}
