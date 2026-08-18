package com.algogyeyak.auth.service;

import com.algogyeyak.global.error.ErrorCode;
import com.algogyeyak.global.exception.BusinessException;
import com.algogyeyak.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mail.MailException;
import org.springframework.mail.MailSendException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmailVerificationServiceTest {

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> valueOps = mock(ValueOperations.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final EmailService emailService = mock(EmailService.class);
    private final EmailVerificationService service =
            new EmailVerificationService(redisTemplate, userRepository, emailService);

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        ReflectionTestUtils.setField(service, "codeValiditySeconds", 300L);
        ReflectionTestUtils.setField(service, "resendCooldownSeconds", 60L);
        ReflectionTestUtils.setField(service, "maxAttempts", 5);
        ReflectionTestUtils.setField(service, "verifiedTicketValiditySeconds", 1800L);
    }

    @Test
    void requestCodeThrowsWhenEmailAlreadyRegistered() {
        when(userRepository.existsByEmail("test@example.com")).thenReturn(true);
        when(valueOps.setIfAbsent(eq("auth:email-verify:cooldown:test@example.com"), eq("1"), any(Duration.class)))
                .thenReturn(true);

        BusinessException exception = assertThrows(BusinessException.class, () -> service.requestCode("test@example.com"));

        assertEquals(ErrorCode.AUTH_EMAIL_VERIFICATION_EMAIL_ALREADY_EXISTS, exception.getErrorCode());
        verify(emailService, never()).sendVerificationCode(anyString(), anyString());
    }

    // 회귀 테스트 - existsByEmail 체크가 쿨다운 확인보다 먼저 실행되면, 이미 가입된 이메일에 대해
    // 무제한 속도로 이 엔드포인트를 호출해 이메일 존재 여부를 빠르게 열거할 수 있었다. 지금은
    // 쿨다운이 먼저 걸려야 한다 - 같은 이메일로 쿨다운 내에 재요청하면 존재 여부와 무관하게
    // AUTH_EMAIL_VERIFICATION_TOO_MANY_REQUESTS로 막혀야 한다.
    @Test
    void requestCodeAppliesCooldownEvenWhenEmailAlreadyRegistered() {
        when(userRepository.existsByEmail("test@example.com")).thenReturn(true);
        when(valueOps.setIfAbsent(eq("auth:email-verify:cooldown:test@example.com"), eq("1"), any(Duration.class)))
                .thenReturn(false);

        BusinessException exception = assertThrows(BusinessException.class, () -> service.requestCode("test@example.com"));

        assertEquals(ErrorCode.AUTH_EMAIL_VERIFICATION_TOO_MANY_REQUESTS, exception.getErrorCode());
        // 쿨다운에 막혔으므로 계정 존재 확인까지 갈 필요가 없다.
        verify(userRepository, never()).existsByEmail(anyString());
    }

    @Test
    void requestCodeThrowsWhenCooldownActive() {
        when(userRepository.existsByEmail("test@example.com")).thenReturn(false);
        when(valueOps.setIfAbsent(eq("auth:email-verify:cooldown:test@example.com"), eq("1"), any(Duration.class)))
                .thenReturn(false);

        BusinessException exception = assertThrows(BusinessException.class, () -> service.requestCode("test@example.com"));

        assertEquals(ErrorCode.AUTH_EMAIL_VERIFICATION_TOO_MANY_REQUESTS, exception.getErrorCode());
        verify(emailService, never()).sendVerificationCode(anyString(), anyString());
    }

    @Test
    void requestCodeSendsEmailAndStoresHashedCode() {
        when(userRepository.existsByEmail("test@example.com")).thenReturn(false);
        when(valueOps.setIfAbsent(eq("auth:email-verify:cooldown:test@example.com"), eq("1"), any(Duration.class)))
                .thenReturn(true);

        service.requestCode("  Test@Example.COM  ");

        verify(emailService).sendVerificationCode(eq("test@example.com"), anyString());
        verify(valueOps).set(eq("auth:email-verify:code-hash:test@example.com"), anyString(), any(Duration.class));
    }

    @Test
    void requestCodeWrapsMailFailureAsBusinessException() {
        when(userRepository.existsByEmail("test@example.com")).thenReturn(false);
        when(valueOps.setIfAbsent(anyString(), eq("1"), any(Duration.class))).thenReturn(true);
        doThrow(new MailSendException("smtp down")).when(emailService).sendVerificationCode(anyString(), anyString());

        BusinessException exception = assertThrows(BusinessException.class, () -> service.requestCode("test@example.com"));

        assertEquals(ErrorCode.EMAIL_SEND_FAILED, exception.getErrorCode());
        // 회귀 테스트 - 메일 발송이 서버 쪽 이유로 실패했는데 쿨다운만 남으면, 사용자가 코드를
        // 받지도 못한 채 60초를 그냥 기다려야 한다. 발송 실패는 쿨다운을 풀어 즉시 재시도를
        // 허용해야 한다.
        verify(redisTemplate).delete("auth:email-verify:cooldown:test@example.com");
    }

    @Test
    void requestCodeThrowsServiceUnavailableWhenRedisFails() {
        when(userRepository.existsByEmail("test@example.com")).thenReturn(false);
        when(valueOps.setIfAbsent(anyString(), eq("1"), any(Duration.class)))
                .thenThrow(new QueryTimeoutException("redis down"));

        BusinessException exception = assertThrows(BusinessException.class, () -> service.requestCode("test@example.com"));

        assertEquals(ErrorCode.AUTH_TOKEN_STORE_UNAVAILABLE, exception.getErrorCode());
    }

    @Test
    void confirmCodeThrowsWhenNoCodeStored() {
        when(valueOps.get("auth:email-verify:code-hash:test@example.com")).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.confirmCode("test@example.com", "123456"));

        assertEquals(ErrorCode.AUTH_EMAIL_VERIFICATION_CODE_INVALID, exception.getErrorCode());
    }

    @Test
    void confirmCodeThrowsWhenCodeDoesNotMatch() {
        when(valueOps.get("auth:email-verify:code-hash:test@example.com")).thenReturn("some-other-hash");
        when(valueOps.increment("auth:email-verify:attempts:test@example.com")).thenReturn(1L);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.confirmCode("test@example.com", "123456"));

        assertEquals(ErrorCode.AUTH_EMAIL_VERIFICATION_CODE_INVALID, exception.getErrorCode());
    }

    @Test
    void confirmCodeThrowsAndInvalidatesCodeWhenAttemptsExceeded() {
        when(valueOps.get("auth:email-verify:code-hash:test@example.com")).thenReturn("some-other-hash");
        when(valueOps.increment("auth:email-verify:attempts:test@example.com")).thenReturn(6L);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.confirmCode("test@example.com", "123456"));

        assertEquals(ErrorCode.AUTH_EMAIL_VERIFICATION_CODE_INVALID, exception.getErrorCode());
        verify(redisTemplate).delete("auth:email-verify:code-hash:test@example.com");
    }

    @Test
    void confirmCodeThrowsServiceUnavailableWhenRedisFails() {
        when(valueOps.get(anyString())).thenThrow(new QueryTimeoutException("redis down"));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.confirmCode("test@example.com", "123456"));

        assertEquals(ErrorCode.AUTH_TOKEN_STORE_UNAVAILABLE, exception.getErrorCode());
    }

    @Test
    void isVerifiedReturnsTrueWhenTicketExists() {
        when(redisTemplate.hasKey("auth:email-verify:verified:test@example.com")).thenReturn(true);

        assertEquals(true, service.isVerified("test@example.com"));
    }

    @Test
    void isVerifiedReturnsFalseWhenTicketMissing() {
        when(redisTemplate.hasKey("auth:email-verify:verified:test@example.com")).thenReturn(false);

        assertEquals(false, service.isVerified("test@example.com"));
    }
}
