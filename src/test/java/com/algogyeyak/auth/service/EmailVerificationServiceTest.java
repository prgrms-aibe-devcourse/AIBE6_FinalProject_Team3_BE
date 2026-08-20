package com.algogyeyak.auth.service;

import com.algogyeyak.global.error.ErrorCode;
import com.algogyeyak.global.exception.BusinessException;
import com.algogyeyak.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.mail.MailSendException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
        // EmailService.sendVerificationCode()는 이제 @Async라 CompletableFuture<Void>를
        // 반환한다(이 목은 Spring 프록시 없이 직접 호출되므로 항상 이미 완료된 future를 반환하게
        // 스텁한다) - 개별 테스트가 실패 시나리오를 검증할 때만 이 기본값을 덮어쓴다.
        when(emailService.sendVerificationCode(anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
    }

    @Test
    void requestCodeThrowsWhenEmailAlreadyRegistered() {
        when(userRepository.existsByEmail("test@example.com")).thenReturn(true);
        when(valueOps.setIfAbsent(eq("auth:email-verify:cooldown:test@example.com"), anyString(), any(Duration.class)))
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
        when(valueOps.setIfAbsent(eq("auth:email-verify:cooldown:test@example.com"), anyString(), any(Duration.class)))
                .thenReturn(false);

        BusinessException exception = assertThrows(BusinessException.class, () -> service.requestCode("test@example.com"));

        assertEquals(ErrorCode.AUTH_EMAIL_VERIFICATION_TOO_MANY_REQUESTS, exception.getErrorCode());
        // 쿨다운에 막혔으므로 계정 존재 확인까지 갈 필요가 없다.
        verify(userRepository, never()).existsByEmail(anyString());
    }

    @Test
    void requestCodeThrowsWhenCooldownActive() {
        when(userRepository.existsByEmail("test@example.com")).thenReturn(false);
        when(valueOps.setIfAbsent(eq("auth:email-verify:cooldown:test@example.com"), anyString(), any(Duration.class)))
                .thenReturn(false);

        BusinessException exception = assertThrows(BusinessException.class, () -> service.requestCode("test@example.com"));

        assertEquals(ErrorCode.AUTH_EMAIL_VERIFICATION_TOO_MANY_REQUESTS, exception.getErrorCode());
        verify(emailService, never()).sendVerificationCode(anyString(), anyString());
    }

    @Test
    void requestCodeSendsEmailAndStoresHashedCode() {
        when(userRepository.existsByEmail("test@example.com")).thenReturn(false);
        when(valueOps.setIfAbsent(eq("auth:email-verify:cooldown:test@example.com"), anyString(), any(Duration.class)))
                .thenReturn(true);

        service.requestCode("  Test@Example.COM  ");

        // 회귀 테스트(2026-08-20 전수조사) - 발송 코드/저장 해시가 둘 다 anyString()이라, 실수로
        // 원문 코드를 저장하고 해시를 이메일로 보내도(또는 그 반대) 이 테스트는 그대로 통과했다.
        // 실제로 보낸 코드를 캡처해 저장된 해시가 hash(그 코드)와 일치하는지, TTL이 설정값(300초)과
        // 정확히 일치하는지 구체적으로 확인한다.
        ArgumentCaptor<String> sentCodeCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendVerificationCode(eq("test@example.com"), sentCodeCaptor.capture());
        String sentCode = sentCodeCaptor.getValue();

        String expectedHash = ReflectionTestUtils.invokeMethod(EmailVerificationService.class, "hash", sentCode);
        verify(valueOps).set(eq("auth:email-verify:code-hash:test@example.com"), eq(expectedHash),
                eq(Duration.ofSeconds(300)));
    }

    // 발송이 비동기(EmailService의 @Async)로 바뀌면서, 발송 실패는 더 이상 requestCode() 호출
    // 스레드에서 동기 예외로 관측되지 않는다(응답은 이미 나간 뒤 콜백에서 처리됨) - 그래서 이제는
    // BusinessException을 던지는 대신 예외 없이 정상 종료되어야 한다. 다만 회귀 테스트로 지키던
    // 핵심 동작(발송 실패 시 쿨다운 해제)은 그대로 유지된다: 쿨다운은 실제 발송 성공을 전제로 한
    // 제한이므로, 발송이 서버 쪽 이유로 실패했는데 쿨다운만 남으면 사용자가 코드를 받지도 못한 채
    // 60초를 그냥 기다려야 한다.
    @Test
    void requestCodeReleasesCooldownWhenMailSendFails() {
        when(userRepository.existsByEmail("test@example.com")).thenReturn(false);
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        when(emailService.sendVerificationCode(anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new MailSendException("smtp down")));

        service.requestCode("test@example.com");

        verify(redisTemplate).execute(any(RedisScript.class), eq(List.of("auth:email-verify:cooldown:test@example.com")), anyString());
    }

    // 회귀 테스트 - emailTaskExecutor의 큐가 가득 차면 @Async 프록시가 Future를 반환하기도 전에
    // TaskRejectedException을 동기로 던진다(위 mailSendFails 테스트처럼 실패한 Future를 반환하는
    // 것과는 다른 실패 모드). 이 경우도 "발송 실패"와 동일하게 취급돼(쿨다운 해제) 예외 없이
    // 정상 종료돼야 한다 - 그렇지 않으면 이 예외가 그대로 500으로 새어나간다.
    @Test
    void requestCodeReleasesCooldownWhenEmailTaskSubmissionIsRejected() {
        when(userRepository.existsByEmail("test@example.com")).thenReturn(false);
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        when(emailService.sendVerificationCode(anyString(), anyString()))
                .thenThrow(new org.springframework.core.task.TaskRejectedException("queue full"));

        service.requestCode("test@example.com");

        verify(redisTemplate).execute(any(RedisScript.class), eq(List.of("auth:email-verify:cooldown:test@example.com")), anyString());
    }

    @Test
    void requestCodeThrowsServiceUnavailableWhenRedisFails() {
        when(userRepository.existsByEmail("test@example.com")).thenReturn(false);
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class)))
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
