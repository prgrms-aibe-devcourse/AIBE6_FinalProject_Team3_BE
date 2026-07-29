package com.algogyeyak.auth.service;

import com.algogyeyak.global.error.ErrorCode;
import com.algogyeyak.global.exception.BusinessException;
import com.algogyeyak.user.entity.User;
import com.algogyeyak.user.enums.AuthProvider;
import com.algogyeyak.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LocalAuthServiceTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
    private final LocalAuthService localAuthService =
            new LocalAuthService(userRepository, passwordEncoder, mock(PlatformTransactionManager.class));

    @Test
    void signupCreatesLocalUserWithEncodedPassword() {
        when(userRepository.existsByEmail("test@example.com")).thenReturn(false);
        when(userRepository.existsByNickname("테스트유저")).thenReturn(false);
        when(passwordEncoder.encode("password1")).thenReturn("encoded-hash");
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        User user = localAuthService.signup("test@example.com", "password1", "테스트유저");

        assertEquals("test@example.com", user.getEmail());
        assertEquals("테스트유저", user.getNickname());
        assertEquals("encoded-hash", user.getPasswordHash());
        verify(userRepository).saveAndFlush(any(User.class));
    }

    @Test
    void signupNormalizesEmailToLowercaseAndTrimmed() {
        when(userRepository.existsByEmail("test@example.com")).thenReturn(false);
        when(userRepository.existsByNickname("테스트유저")).thenReturn(false);
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
    void signupRecoversAsEmailDuplicateWhenConcurrentSignupHitsUniqueConstraint() {
        when(userRepository.existsByEmail("test@example.com"))
                .thenReturn(false) // 최초 체크 시점
                .thenReturn(true); // INSERT 실패 후 재확인
        when(userRepository.existsByNickname("테스트유저")).thenReturn(false);
        when(userRepository.saveAndFlush(any(User.class)))
                .thenThrow(new DataIntegrityViolationException("unique constraint violation"));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> localAuthService.signup("test@example.com", "password1", "테스트유저"));

        assertEquals(ErrorCode.AUTH_EMAIL_ALREADY_EXISTS, exception.getErrorCode());
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
        User socialUser = User.createOAuthUser("social@example.com", "소셜유저", null, AuthProvider.KAKAO, "999");
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
    void setPasswordSucceedsForOAuthOnlyAccountWithoutCurrentPassword() {
        User user = User.createOAuthUser("social@example.com", "소셜유저", null, AuthProvider.KAKAO, "999");
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
        User user = User.createOAuthUser(null, "소셜유저", null, AuthProvider.KAKAO, "999");
        ReflectionTestUtils.setField(user, "id", 1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> localAuthService.setPassword(1L, null, "newPassword1"));

        assertEquals(ErrorCode.AUTH_EMAIL_REQUIRED_FOR_PASSWORD, exception.getErrorCode());
        assertEquals(null, user.getPasswordHash());
    }

    @Test
    void setPasswordRequiresCurrentPasswordWhenAlreadySet() {
        User user = User.createLocalUser("test@example.com", "encoded-hash", "테스트유저");
        ReflectionTestUtils.setField(user, "id", 1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> localAuthService.setPassword(1L, null, "newPassword1"));

        assertEquals(ErrorCode.AUTH_INVALID_CREDENTIALS, exception.getErrorCode());
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

        assertEquals(ErrorCode.AUTH_INVALID_CREDENTIALS, exception.getErrorCode());
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
        assertEquals(null, admin.getPasswordHash());
    }
}
