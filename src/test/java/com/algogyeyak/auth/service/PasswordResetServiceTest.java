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

import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        ReflectionTestUtils.setField(service, "tokenValiditySeconds", 1800L);
        ReflectionTestUtils.setField(service, "requestCooldownSeconds", 60L);
        ReflectionTestUtils.setField(service, "frontendBaseUrl", "http://localhost:3000");
    }

    @Test
    void requestResetThrowsWhenCooldownActive() {
        when(valueOps.setIfAbsent(eq("auth:password-reset:cooldown:test@example.com"), eq("1"), any(Duration.class)))
                .thenReturn(false);

        BusinessException exception = assertThrows(BusinessException.class, () -> service.requestReset("test@example.com"));

        assertEquals(ErrorCode.AUTH_PASSWORD_RESET_TOO_MANY_REQUESTS, exception.getErrorCode());
        verify(emailService, never()).sendPasswordResetLink(anyString(), anyString());
    }

    @Test
    void requestResetDoesNothingWhenAccountNotFound() {
        when(valueOps.setIfAbsent(anyString(), eq("1"), any(Duration.class))).thenReturn(true);
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.empty());

        service.requestReset("test@example.com");

        verify(emailService, never()).sendPasswordResetLink(anyString(), anyString());
        verify(redisTemplate, never()).execute(any(RedisScript.class), anyList(), any(), any(), any());
    }

    @Test
    void requestResetDoesNothingForSocialOnlyAccount() {
        User socialUser = User.createOAuthUser("test@example.com", "소셜유저", null);
        when(valueOps.setIfAbsent(anyString(), eq("1"), any(Duration.class))).thenReturn(true);
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(socialUser));

        service.requestReset("test@example.com");

        verify(emailService, never()).sendPasswordResetLink(anyString(), anyString());
    }

    @Test
    void requestResetDoesNothingForWithdrawnAccount() {
        User user = localUser(1L);
        user.withdraw();
        when(valueOps.setIfAbsent(anyString(), eq("1"), any(Duration.class))).thenReturn(true);
        when(userRepository.findByEmail(anyString())).thenReturn(Optional.of(user));

        service.requestReset("test@example.com");

        verify(emailService, never()).sendPasswordResetLink(anyString(), anyString());
    }

    @Test
    void requestResetIssuesTokenAndSendsEmailForEligibleAccount() {
        User user = localUser(1L);
        when(valueOps.setIfAbsent(anyString(), eq("1"), any(Duration.class))).thenReturn(true);
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(user));
        doReturn(1L).when(redisTemplate).execute(any(RedisScript.class), anyList(), any(), any(), any());

        service.requestReset("test@example.com");

        ArgumentCaptor<String> linkCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendPasswordResetLink(eq("test@example.com"), linkCaptor.capture());
        assertEquals(true, linkCaptor.getValue().startsWith("http://localhost:3000/reset-password?token="));
    }

    @Test
    void requestResetWrapsMailFailureAsBusinessException() {
        User user = localUser(1L);
        when(valueOps.setIfAbsent(anyString(), eq("1"), any(Duration.class))).thenReturn(true);
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(user));
        doReturn(1L).when(redisTemplate).execute(any(RedisScript.class), anyList(), any(), any(), any());
        doThrow(new MailSendException("smtp down")).when(emailService)
                .sendPasswordResetLink(anyString(), anyString());

        BusinessException exception = assertThrows(BusinessException.class, () -> service.requestReset("test@example.com"));

        assertEquals(ErrorCode.EMAIL_SEND_FAILED, exception.getErrorCode());
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
        verify(refreshTokenService).revokeAllForUser(1L);
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

    @Test
    void confirmResetThrowsServiceUnavailableWhenRedisFails() {
        doThrow(new QueryTimeoutException("redis down"))
                .when(redisTemplate).execute(any(RedisScript.class), anyList(), anyString());

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.confirmReset("some-token", "newPassword1"));

        assertEquals(ErrorCode.AUTH_TOKEN_STORE_UNAVAILABLE, exception.getErrorCode());
    }
}
