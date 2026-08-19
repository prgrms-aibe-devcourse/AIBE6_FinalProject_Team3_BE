package com.algogyeyak.auth.service;

import com.algogyeyak.global.error.ErrorCode;
import com.algogyeyak.global.exception.BusinessException;
import com.algogyeyak.user.entity.User;
import com.algogyeyak.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LocalAuthServiceTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
    private final EmailVerificationService emailVerificationService = mock(EmailVerificationService.class);
    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> valueOps = mock(ValueOperations.class);
    private final LocalAuthService localAuthService = new LocalAuthService(
            userRepository, passwordEncoder, emailVerificationService, mock(PlatformTransactionManager.class),
            redisTemplate);

    {
        // 대부분의 테스트는 로그인 시도 횟수 제한과 무관하므로, opsForValue().increment()가
        // 스텁되지 않은 기본 상태(Mockito 기본 응답 = null)에서는 attempts != null 가드 덕분에
        // 제한 로직 자체를 타지 않는다 - 아래에서 rate-limit을 실제로 검증하는 테스트만 별도로
        // increment()를 스텁한다.
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
    }

    @Test
    void signupCreatesLocalUserWithEncodedPassword() {
        when(userRepository.existsByEmail("test@example.com")).thenReturn(false);
        when(userRepository.existsByNickname("테스트유저")).thenReturn(false);
        when(emailVerificationService.isVerified("test@example.com")).thenReturn(true);
        when(passwordEncoder.encode("password1")).thenReturn("encoded-hash");
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        User user = localAuthService.signup("test@example.com", "password1", "테스트유저");

        assertEquals("test@example.com", user.getEmail());
        assertEquals("테스트유저", user.getNickname());
        assertEquals("encoded-hash", user.getPasswordHash());
        verify(userRepository).saveAndFlush(any(User.class));
        verify(emailVerificationService).consumeVerified("test@example.com");
    }

    @Test
    void signupNormalizesEmailToLowercaseAndTrimmed() {
        when(userRepository.existsByEmail("test@example.com")).thenReturn(false);
        when(userRepository.existsByNickname("테스트유저")).thenReturn(false);
        when(emailVerificationService.isVerified("test@example.com")).thenReturn(true);
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        User user = localAuthService.signup("  Test@Example.COM  ", "password1", "테스트유저");

        assertEquals("test@example.com", user.getEmail());
        verify(userRepository).existsByEmail("test@example.com");
    }

    @Test
    void signupThrowsWhenEmailAlreadyExists() {
        when(userRepository.existsByEmail("test@example.com")).thenReturn(true);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> localAuthService.signup("test@example.com", "password1", "테스트유저"));

        assertEquals(ErrorCode.AUTH_EMAIL_ALREADY_EXISTS, exception.getErrorCode());
        verify(userRepository, never()).saveAndFlush(any(User.class));
    }

    @Test
    void signupThrowsWhenNicknameAlreadyExists() {
        when(userRepository.existsByEmail("test@example.com")).thenReturn(false);
        when(userRepository.existsByNickname("테스트유저")).thenReturn(true);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> localAuthService.signup("test@example.com", "password1", "테스트유저"));

        assertEquals(ErrorCode.AUTH_NICKNAME_ALREADY_EXISTS, exception.getErrorCode());
        verify(userRepository, never()).saveAndFlush(any(User.class));
    }

    @Test
    void signupThrowsWhenEmailNotVerified() {
        when(userRepository.existsByEmail("test@example.com")).thenReturn(false);
        when(userRepository.existsByNickname("테스트유저")).thenReturn(false);
        when(emailVerificationService.isVerified("test@example.com")).thenReturn(false);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> localAuthService.signup("test@example.com", "password1", "테스트유저"));

        assertEquals(ErrorCode.AUTH_EMAIL_NOT_VERIFIED, exception.getErrorCode());
        verify(userRepository, never()).saveAndFlush(any(User.class));
    }

    @Test
    void signupKeepsVerifiedTicketWhenAccountCreationFailsSoUserCanRetry() {
        // 인증까지는 성공했는데(예: 이메일 인증 완료 후) 닉네임 중복 등으로 계정 생성이 실패하면,
        // 사용자가 이메일 인증부터 다시 하지 않고 나머지 폼만 고쳐 재시도할 수 있어야 한다 - 티켓을
        // 소비하면 안 된다.
        when(userRepository.existsByEmail("test@example.com")).thenReturn(false);
        when(userRepository.existsByNickname("테스트유저")).thenReturn(true);
        when(emailVerificationService.isVerified("test@example.com")).thenReturn(true);

        assertThrows(BusinessException.class,
                () -> localAuthService.signup("test@example.com", "password1", "테스트유저"));

        verify(emailVerificationService, never()).consumeVerified(any());
    }

    @Test
    void signupRecoversAsEmailDuplicateWhenConcurrentSignupHitsUniqueConstraint() {
        when(userRepository.existsByEmail("test@example.com"))
                .thenReturn(false) // 최초 체크 시점
                .thenReturn(true); // INSERT 실패 후 재확인
        when(userRepository.existsByNickname("테스트유저")).thenReturn(false);
        when(emailVerificationService.isVerified("test@example.com")).thenReturn(true);
        when(userRepository.saveAndFlush(any(User.class)))
                .thenThrow(new DataIntegrityViolationException("unique constraint violation"));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> localAuthService.signup("test@example.com", "password1", "테스트유저"));

        assertEquals(ErrorCode.AUTH_EMAIL_ALREADY_EXISTS, exception.getErrorCode());
    }

    @Test
    void signupRecoversAsNicknameDuplicateWhenConcurrentSignupHitsUniqueConstraint() {
        when(userRepository.existsByEmail("test@example.com")).thenReturn(false);
        when(userRepository.existsByNickname("테스트유저"))
                .thenReturn(false) // 최초 체크 시점
                .thenReturn(true); // INSERT 실패 후 재확인
        when(emailVerificationService.isVerified("test@example.com")).thenReturn(true);
        when(userRepository.saveAndFlush(any(User.class)))
                .thenThrow(new DataIntegrityViolationException("unique constraint violation"));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> localAuthService.signup("test@example.com", "password1", "테스트유저"));

        assertEquals(ErrorCode.AUTH_NICKNAME_ALREADY_EXISTS, exception.getErrorCode());
    }

    @Test
    void signupRethrowsOriginalExceptionWhenConstraintViolationMatchesNeitherEmailNorNickname() {
        // 이메일도 닉네임도 재확인 결과 중복이 아니라면 - 무조건 닉네임 탓이라고 단정하지 않고
        // 원래 예외를 그대로 던져야 한다 (예: 다른 유니크 제약이 추가되거나, 일시적 DB 문제).
        when(userRepository.existsByEmail("test@example.com")).thenReturn(false);
        when(userRepository.existsByNickname("테스트유저")).thenReturn(false);
        when(emailVerificationService.isVerified("test@example.com")).thenReturn(true);
        DataIntegrityViolationException original = new DataIntegrityViolationException("unknown constraint violation");
        when(userRepository.saveAndFlush(any(User.class))).thenThrow(original);

        DataIntegrityViolationException thrown = assertThrows(DataIntegrityViolationException.class,
                () -> localAuthService.signup("test@example.com", "password1", "테스트유저"));

        assertEquals(original, thrown);
    }

    // signup() 직후 refresh token 발급 실패 등으로 세션을 만들지 못했을 때 AuthController가 호출하는
    // 보상 트랜잭션 - 지금까지 AuthControllerTest의 MockMvc 레벨 테스트에서만 간접적으로 거쳐갔을 뿐,
    // 이 서비스 메서드를 직접 호출하는 단위 테스트가 없었다.
    @Test
    void deleteNewlyCreatedUserAfterSessionSetupFailureDeletesTheUser() {
        localAuthService.deleteNewlyCreatedUserAfterSessionSetupFailure(1L);

        verify(userRepository).deleteById(1L);
    }

    @Test
    void loginSucceedsWithMatchingPassword() {
        User user = User.createLocalUser("test@example.com", "encoded-hash", "테스트유저");
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("password1", "encoded-hash")).thenReturn(true);

        User result = localAuthService.login("test@example.com", "password1");

        assertEquals(user, result);
    }

    @Test
    void loginNormalizesEmailBeforeLookup() {
        User user = User.createLocalUser("test@example.com", "encoded-hash", "테스트유저");
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("password1", "encoded-hash")).thenReturn(true);

        User result = localAuthService.login("  Test@Example.COM  ", "password1");

        assertEquals(user, result);
    }

    @Test
    void loginThrowsWhenEmailNotFound() {
        when(userRepository.findByEmail("unknown@example.com")).thenReturn(Optional.empty());

        BusinessException exception = assertThrows(BusinessException.class,
                () -> localAuthService.login("unknown@example.com", "password1"));

        assertEquals(ErrorCode.AUTH_INVALID_CREDENTIALS, exception.getErrorCode());
    }

    @Test
    void loginRunsBcryptComparisonEvenWhenEmailNotFound() {
        // 회귀 테스트(타이밍 사이드채널) - 계정이 없어도 실제 비밀번호 불일치 경로와 같은 비용의
        // BCrypt 비교를 한 번 수행해야, 응답 시간만으로 "이 이메일이 존재하는지"를 알아낼 수 없다.
        when(userRepository.findByEmail("unknown@example.com")).thenReturn(Optional.empty());

        assertThrows(BusinessException.class, () -> localAuthService.login("unknown@example.com", "password1"));

        verify(passwordEncoder).matches(eq("password1"), any());
    }

    @Test
    void loginRunsBcryptComparisonEvenForSocialOnlyAccount() {
        // 회귀 테스트(타이밍 사이드채널) - passwordHash가 없는 소셜 전용 계정도 더미 해시로
        // BCrypt 비교를 한 번 수행해야 한다.
        User socialUser = User.createOAuthUser("social2@example.com", "소셜유저2", null);
        when(userRepository.findByEmail("social2@example.com")).thenReturn(Optional.of(socialUser));

        assertThrows(BusinessException.class, () -> localAuthService.login("social2@example.com", "password1"));

        verify(passwordEncoder).matches(eq("password1"), any());
    }

    @Test
    void loginThrowsWhenPasswordDoesNotMatch() {
        User user = User.createLocalUser("test@example.com", "encoded-hash", "테스트유저");
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong-password", "encoded-hash")).thenReturn(false);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> localAuthService.login("test@example.com", "wrong-password"));

        assertEquals(ErrorCode.AUTH_INVALID_CREDENTIALS, exception.getErrorCode());
    }

    @Test
    void loginThrowsForSocialOnlyAccountWithoutRevealingItExists() {
        // 소셜 전용 가입은 passwordHash가 없다 — 계정 존재 여부를 드러내지 않도록 일반 자격 증명 오류와 동일하게 처리한다.
        User socialUser = User.createOAuthUser("social@example.com", "소셜유저", null);
        when(userRepository.findByEmail("social@example.com")).thenReturn(Optional.of(socialUser));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> localAuthService.login("social@example.com", "password1"));

        assertEquals(ErrorCode.AUTH_INVALID_CREDENTIALS, exception.getErrorCode());
    }

    @Test
    void loginThrowsWhenUserWithdrawn() {
        User user = User.createLocalUser("test@example.com", "encoded-hash", "테스트유저");
        user.withdraw();
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(user));

        assertThrows(BusinessException.class, () -> localAuthService.login("test@example.com", "password1"));
    }

    @Test
    void loginThrowsForSuspendedAccountWithoutRevealingItExists() {
        // (2026-08-12 추가) 정지된 계정도 존재하지 않는 이메일과 동일한 AUTH_INVALID_CREDENTIALS로
        // 응답해야 한다 — 이번 세션에서 OAuth 로그인(CustomOAuth2UserService.rejectIfBlocked)의
        // account_blocked 노출을 oauth_login_failed로 통일한 것과 같은 "계정 존재/정지 여부
        // 비노출" 원칙을 로컬 로그인 경로에서도 회귀 테스트로 고정해둔다 - 지금까지 이 경로엔
        // withdrawn 계정 테스트만 있었고 suspended 계정 테스트 자체가 없었다.
        User user = User.createLocalUser("suspended@example.com", "encoded-hash", "정지유저");
        user.suspend();
        when(userRepository.findByEmail("suspended@example.com")).thenReturn(Optional.of(user));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> localAuthService.login("suspended@example.com", "password1"));

        assertEquals(ErrorCode.AUTH_INVALID_CREDENTIALS, exception.getErrorCode());
    }

    // 회귀 테스트 - login()에 무차별대입 방지 장치가 전혀 없어(EmailVerificationService.confirmCode()의
    // maxAttempts와 달리) 같은 이메일로 무제한 로그인 시도가 가능했던 문제를 막는다.
    @Test
    void loginThrowsTooManyAttemptsWhenAttemptCounterExceedsLimit() {
        ReflectionTestUtils.setField(localAuthService, "loginMaxAttempts", 10);
        ReflectionTestUtils.setField(localAuthService, "loginLockoutWindowSeconds", 300L);
        when(valueOps.increment(anyString())).thenReturn(11L);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> localAuthService.login("unknown@example.com", "password1"));

        assertEquals(ErrorCode.AUTH_TOO_MANY_LOGIN_ATTEMPTS, exception.getErrorCode());
        // 시도 횟수 초과 시엔 실제 계정 조회/BCrypt 비교까지 갈 필요가 없다 - 바로 거부한다.
        verify(userRepository, never()).findByEmail(any());
    }

    // 회귀 테스트 - 존재하지 않는 이메일에도 존재하는 이메일과 동일하게 카운트해야
    // 계정 존재 여부가 시도 제한 발동 시점 차이로 새어나가지 않는다.
    @Test
    void loginRateLimitCountsAttemptsRegardlessOfAccountExistence() {
        ReflectionTestUtils.setField(localAuthService, "loginMaxAttempts", 10);
        ReflectionTestUtils.setField(localAuthService, "loginLockoutWindowSeconds", 300L);
        when(valueOps.increment(anyString())).thenReturn(11L);

        BusinessException unknown = assertThrows(BusinessException.class,
                () -> localAuthService.login("unknown@example.com", "password1"));
        assertEquals(ErrorCode.AUTH_TOO_MANY_LOGIN_ATTEMPTS, unknown.getErrorCode());

        User user = User.createLocalUser("known@example.com", "encoded-hash", "테스트유저");
        when(userRepository.findByEmail("known@example.com")).thenReturn(Optional.of(user));
        BusinessException known = assertThrows(BusinessException.class,
                () -> localAuthService.login("known@example.com", "password1"));
        assertEquals(ErrorCode.AUTH_TOO_MANY_LOGIN_ATTEMPTS, known.getErrorCode());
    }

    @Test
    void loginSucceedsAndResetsAttemptCounterWithinLimit() {
        ReflectionTestUtils.setField(localAuthService, "loginMaxAttempts", 10);
        ReflectionTestUtils.setField(localAuthService, "loginLockoutWindowSeconds", 300L);
        when(valueOps.increment(anyString())).thenReturn(3L);
        User user = User.createLocalUser("test@example.com", "encoded-hash", "테스트유저");
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("password1", "encoded-hash")).thenReturn(true);

        User result = localAuthService.login("test@example.com", "password1");

        assertEquals(user, result);
        verify(redisTemplate).delete("auth:login:attempts:test@example.com");
    }

    // 회귀 테스트 - increment() 성공 후 expire()만 별도로 실패하면 카운터 키가 TTL 없이 영구히
    // 남아 이후 그 이메일이 사실상 영구 잠금될 수 있었던 문제(setIfAbsent로 키 생성과 동시에
    // TTL을 먼저 확정해두는 방식으로 수정) - increment 이전에 항상 setIfAbsent가 호출되는지 고정.
    @Test
    void loginEstablishesAttemptCounterTtlBeforeIncrementing() {
        ReflectionTestUtils.setField(localAuthService, "loginMaxAttempts", 10);
        ReflectionTestUtils.setField(localAuthService, "loginLockoutWindowSeconds", 300L);
        when(valueOps.increment(anyString())).thenReturn(3L);
        User user = User.createLocalUser("test@example.com", "encoded-hash", "테스트유저");
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("password1", "encoded-hash")).thenReturn(true);

        localAuthService.login("test@example.com", "password1");

        verify(valueOps).setIfAbsent(
                "auth:login:attempts:test@example.com", "0", java.time.Duration.ofSeconds(300L));
    }

    // 회귀 테스트 - Redis 장애 시에는 가용성을 우선해 로그인 자체를 막지 않아야 한다(다른
    // Redis 기반 카운터들의 fail-open/기존 로그인 가용성 우선 정책과 동일).
    @Test
    void loginProceedsWhenRedisFailsDuringAttemptCounting() {
        when(valueOps.increment(anyString())).thenThrow(new QueryTimeoutException("redis down"));
        User user = User.createLocalUser("test@example.com", "encoded-hash", "테스트유저");
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("password1", "encoded-hash")).thenReturn(true);

        User result = localAuthService.login("test@example.com", "password1");

        assertEquals(user, result);
    }

    // 위 loginProceedsWhenRedisFailsDuringAttemptCounting()의 대칭 케이스 - 로그인 자체는 이미
    // 성공한 뒤 시도 횟수를 리셋하는 delete()만 Redis 장애로 실패해도, 로그인 결과에는 영향을 주면
    // 안 된다(카운터는 TTL로 자연 정리되므로 무시해도 무방하다는 판단, LocalAuthService.login()의
    // 해당 catch(DataAccessException) 주석 참고).
    @Test
    void loginSucceedsWhenRedisFailsDuringAttemptCounterReset() {
        ReflectionTestUtils.setField(localAuthService, "loginMaxAttempts", 10);
        ReflectionTestUtils.setField(localAuthService, "loginLockoutWindowSeconds", 300L);
        when(valueOps.increment(anyString())).thenReturn(3L);
        User user = User.createLocalUser("test@example.com", "encoded-hash", "테스트유저");
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("password1", "encoded-hash")).thenReturn(true);
        doThrow(new QueryTimeoutException("redis down"))
                .when(redisTemplate).delete("auth:login:attempts:test@example.com");

        User result = localAuthService.login("test@example.com", "password1");

        assertEquals(user, result);
    }

    // 경계값 테스트 - attempts가 loginMaxAttempts를 "초과"할 때만(> 비교) 잠가야 하고, 정확히
    // 한도에 도달한 시도는 여전히 성공해야 한다(>= 비교였다면 여기서 잘못 잠겼을 것). 기존
    // 테스트들은 attempts=11 vs loginMaxAttempts=10처럼 이미 초과한 값만 다뤘다.
    @Test
    void loginSucceedsWhenAttemptsExactlyAtMaxAttemptsLimit() {
        ReflectionTestUtils.setField(localAuthService, "loginMaxAttempts", 10);
        ReflectionTestUtils.setField(localAuthService, "loginLockoutWindowSeconds", 300L);
        when(valueOps.increment(anyString())).thenReturn(10L);
        User user = User.createLocalUser("test@example.com", "encoded-hash", "테스트유저");
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("password1", "encoded-hash")).thenReturn(true);

        User result = localAuthService.login("test@example.com", "password1");

        assertEquals(user, result);
    }

    @Test
    void setPasswordSucceedsForOAuthOnlyAccountWithoutCurrentPassword() {
        User user = User.createOAuthUser("social@example.com", "소셜유저", null);
        ReflectionTestUtils.setField(user, "id", 1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(passwordEncoder.encode("newPassword1")).thenReturn("new-encoded-hash");

        localAuthService.setPassword(1L, null, "newPassword1");

        assertEquals("new-encoded-hash", user.getPasswordHash());
    }

    @Test
    void setPasswordThrowsWhenAccountHasNoEmail() {
        // 카카오는 현재 profile_nickname 스코프만 요청해 이메일 동의항목이 없다(application.yml
        // 참고) — 검증된 이메일이 없으면 CustomOAuth2UserService가 email=null로 계정을 만든다.
        // login()은 email+passwordHash 조합으로만 계정을 찾으므로, 이런 계정에 비밀번호를 설정해도
        // 그걸로 로그인할 방법이 없다 — 프론트가 "같은 이메일로 로그인할 수도 있어요"라고 안내해놓고
        // 실제로는 불가능한 상황을 막기 위해 여기서 거부해야 한다.
        User user = User.createOAuthUser(null, "소셜유저", null);
        ReflectionTestUtils.setField(user, "id", 1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> localAuthService.setPassword(1L, null, "newPassword1"));

        assertEquals(ErrorCode.AUTH_EMAIL_REQUIRED_FOR_PASSWORD, exception.getErrorCode());
        assertEquals(HttpStatus.FORBIDDEN, exception.getStatus());
        assertEquals(null, user.getPasswordHash());
    }

    @Test
    void setPasswordRequiresCurrentPasswordWhenAlreadySet() {
        User user = User.createLocalUser("test@example.com", "encoded-hash", "테스트유저");
        ReflectionTestUtils.setField(user, "id", 1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> localAuthService.setPassword(1L, null, "newPassword1"));

        assertEquals(ErrorCode.AUTH_CURRENT_PASSWORD_MISMATCH, exception.getErrorCode());
        assertEquals(HttpStatus.FORBIDDEN, exception.getStatus());
        assertEquals("encoded-hash", user.getPasswordHash());
    }

    @Test
    void setPasswordRejectsWrongCurrentPassword() {
        User user = User.createLocalUser("test@example.com", "encoded-hash", "테스트유저");
        ReflectionTestUtils.setField(user, "id", 1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong-current", "encoded-hash")).thenReturn(false);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> localAuthService.setPassword(1L, "wrong-current", "newPassword1"));

        assertEquals(ErrorCode.AUTH_CURRENT_PASSWORD_MISMATCH, exception.getErrorCode());
        assertEquals(HttpStatus.FORBIDDEN, exception.getStatus());
    }

    @Test
    void setPasswordSucceedsWithCorrectCurrentPassword() {
        User user = User.createLocalUser("test@example.com", "encoded-hash", "테스트유저");
        ReflectionTestUtils.setField(user, "id", 1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("oldPassword1", "encoded-hash")).thenReturn(true);
        when(passwordEncoder.encode("newPassword1")).thenReturn("new-encoded-hash");

        localAuthService.setPassword(1L, "oldPassword1", "newPassword1");

        assertEquals("new-encoded-hash", user.getPasswordHash());
        // 회귀 테스트 - 본인이 자발적으로 비밀번호를 바꾼 경우도 JwtAuthenticationFilter가 기존
        // access token을 무효화할 수 있도록 passwordChangedAt이 찍혀야 한다(비밀번호 재설정
        // 경로만 찍고 이 경로를 빠뜨리면, 탈취된 이전 access token이 계속 유효하게 남는다).
        assertNotNull(user.getPasswordChangedAt());
        // 회귀 테스트 - JWT의 iat(NumericDate)는 초 단위 정수라 나노초가 항상 0인데,
        // passwordChangedAt에 나노초까지 있는 값을 그대로 저장하면 같은 초 안에서 발급된 정상
        // 토큰이 "변경 이전"으로 잘못 비교돼 거부될 수 있다(JwtAuthenticationFilter 참고) -
        // 저장 시점에 초 단위로 truncate되어야 한다.
        assertEquals(0, user.getPasswordChangedAt().getNano());
    }

    @Test
    void setPasswordThrowsWhenUserNotFoundOrWithdrawn() {
        when(userRepository.findById(1L)).thenReturn(Optional.empty());

        assertThrows(BusinessException.class, () -> localAuthService.setPassword(1L, null, "newPassword1"));
    }

    @Test
    void setPasswordRejectsDevLoginAdminAccountEvenWithoutExistingPassword() {
        ReflectionTestUtils.setField(localAuthService, "devLoginEmail", "admin@algogyeyak.local");
        User admin = User.createLocalUser("admin@algogyeyak.local", null, "관리자");
        ReflectionTestUtils.setField(admin, "id", 1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(admin));

        // dev-login 관리자 계정은 비밀번호가 없는 상태(existingHash == null)라 일반적인 "최초 설정"
        // 경로를 타지만, 이 계정만은 예외적으로 항상 거부되어야 한다 — 그래야 dev-login 세션
        // 하나로 이 계정에 영구적인 로컬 로그인 수단을 만드는 것을 막을 수 있다.
        BusinessException exception = assertThrows(BusinessException.class,
                () -> localAuthService.setPassword(1L, null, "newPassword1"));

        assertEquals(ErrorCode.FORBIDDEN, exception.getErrorCode());
        assertEquals(HttpStatus.FORBIDDEN, exception.getStatus());
        assertEquals(null, admin.getPasswordHash());
    }
}
