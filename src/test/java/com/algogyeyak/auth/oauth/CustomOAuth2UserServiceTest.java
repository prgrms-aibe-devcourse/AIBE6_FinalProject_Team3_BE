package com.algogyeyak.auth.oauth;

import com.algogyeyak.user.enums.AuthProvider;
import com.algogyeyak.user.entity.User;
import com.algogyeyak.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CustomOAuth2UserServiceTest {

    private static OAuth2User kakaoOAuth2User(long id, String nickname, String profileImageUrl, String email) {
        return kakaoOAuth2User(id, nickname, profileImageUrl, email, true);
    }

    private static OAuth2User kakaoOAuth2User(
            long id, String nickname, String profileImageUrl, String email, boolean emailVerified) {
        Map<String, Object> profile = new HashMap<>();
        if (nickname != null) {
            profile.put("nickname", nickname);
        }
        if (profileImageUrl != null) {
            profile.put("profile_image_url", profileImageUrl);
        }

        Map<String, Object> kakaoAccount = new HashMap<>();
        if (email != null) {
            kakaoAccount.put("email", email);
            kakaoAccount.put("is_email_verified", emailVerified);
        }
        if (!profile.isEmpty()) {
            kakaoAccount.put("profile", profile);
        }

        Map<String, Object> attributes = new HashMap<>();
        attributes.put("id", id);
        if (!kakaoAccount.isEmpty()) {
            attributes.put("kakao_account", kakaoAccount);
        }

        List<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("ROLE_USER"));
        return new DefaultOAuth2User(authorities, attributes, "id");
    }

    @Test
    void createsNewUserWhenNoExistingAccountForProvider() {
        UserRepository repository = mock(UserRepository.class);
        CustomOAuth2UserService service =
                new CustomOAuth2UserService(repository, mock(PlatformTransactionManager.class));

        when(repository.findByProviderAndProviderId(AuthProvider.KAKAO, "123")).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        OAuth2User result = service.processOAuth2User("kakao", kakaoOAuth2User(123L, "테스트유저", "http://img", "test@kakao.com"));

        assertInstanceOf(CustomOAuth2User.class, result);
        User user = ((CustomOAuth2User) result).getUser();
        assertEquals("테스트유저", user.getNickname());
        assertEquals("test@kakao.com", user.getEmail());
        assertEquals(AuthProvider.KAKAO, user.getProvider());
        assertEquals("123", user.getProviderId());
        assertFalse(((CustomOAuth2User) result).isLinkedToExistingAccount());

        verify(repository).saveAndFlush(any(User.class));
    }

    @Test
    void reusesExistingUserWithoutOverwritingCustomizedProfile() {
        UserRepository repository = mock(UserRepository.class);
        CustomOAuth2UserService service =
                new CustomOAuth2UserService(repository, mock(PlatformTransactionManager.class));

        // 로그인 이후 프로필 등록/수정 화면에서 닉네임과 사진을 직접 바꾼 상태를 가정한다.
        User existing = User.createOAuthUser("old@kakao.com", "커스텀닉네임", "http://custom", AuthProvider.KAKAO, "123");
        when(repository.findByProviderAndProviderId(AuthProvider.KAKAO, "123")).thenReturn(Optional.of(existing));

        OAuth2User result = service.processOAuth2User("kakao", kakaoOAuth2User(123L, "새닉네임", "http://new", "old@kakao.com"));

        // 재로그인 시 OAuth 제공자 값(새닉네임/http://new)이 아니라 기존에 커스터마이징한 값이 그대로 유지되어야 한다.
        User user = ((CustomOAuth2User) result).getUser();
        assertEquals("커스텀닉네임", user.getNickname());
        assertEquals("http://custom", user.getProfileImageUrl());
        // provider+providerId로 바로 찾은 기존 회원의 재로그인일 뿐, 방금 새로 연동된 게 아니다.
        assertFalse(((CustomOAuth2User) result).isLinkedToExistingAccount());

        verify(repository, never()).saveAndFlush(any(User.class));
    }

    @Test
    void fallsBackToGeneratedNicknameWhenKakaoNicknameMissing() {
        UserRepository repository = mock(UserRepository.class);
        CustomOAuth2UserService service =
                new CustomOAuth2UserService(repository, mock(PlatformTransactionManager.class));

        when(repository.findByProviderAndProviderId(AuthProvider.KAKAO, "999")).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        OAuth2User result = service.processOAuth2User("kakao", kakaoOAuth2User(999L, null, null, null));

        User user = ((CustomOAuth2User) result).getUser();
        assertEquals("kakao_999", user.getNickname());
    }

    @Test
    void reusesWinnerRowWhenConcurrentFirstLoginHitsUniqueConstraint() {
        UserRepository repository = mock(UserRepository.class);
        CustomOAuth2UserService service =
                new CustomOAuth2UserService(repository, mock(PlatformTransactionManager.class));

        User winner = User.createOAuthUser("test@kakao.com", "테스트유저", "http://img", AuthProvider.KAKAO, "123");

        // 첫 조회 시점엔 아직 아무도 없다고 나오지만(레이스), save 시도 시 다른 스레드가 먼저 커밋해서 유니크 제약 위반이 난다.
        when(repository.findByProviderAndProviderId(AuthProvider.KAKAO, "123"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(winner));
        when(repository.saveAndFlush(any(User.class)))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("unique constraint violation"));

        OAuth2User result = service.processOAuth2User("kakao", kakaoOAuth2User(123L, "테스트유저", "http://img", "test@kakao.com"));

        User user = ((CustomOAuth2User) result).getUser();
        assertEquals(winner, user);
    }

    @Test
    void linksNewProviderToExistingAccountWithSameEmailInsteadOfCreatingRow() {
        UserRepository repository = mock(UserRepository.class);
        CustomOAuth2UserService service =
                new CustomOAuth2UserService(repository, mock(PlatformTransactionManager.class));

        // 로컬 이메일/비밀번호로 이미 가입되어 있고, 이번이 처음 하는 구글/카카오 로그인이라고 가정한다.
        User existingLocalUser = User.createLocalUser("shared@example.com", "encoded-hash", "로컬유저");
        when(repository.findByProviderAndProviderId(AuthProvider.KAKAO, "123")).thenReturn(Optional.empty());
        when(repository.findByEmail("shared@example.com")).thenReturn(Optional.of(existingLocalUser));

        OAuth2User result = service.processOAuth2User("kakao", kakaoOAuth2User(123L, "카카오닉네임", "http://img", "shared@example.com"));

        User user = ((CustomOAuth2User) result).getUser();
        assertEquals(existingLocalUser, user);
        // 새 row를 만드는 게 아니라 기존 계정의 provider/providerId만 이번 로그인 수단으로 갱신되어야 한다.
        assertEquals(AuthProvider.KAKAO, user.getProvider());
        assertEquals("123", user.getProviderId());
        // 로컬 로그인이 계속 가능하도록 이메일/비밀번호 해시는 그대로 유지되어야 한다.
        assertEquals("encoded-hash", user.getPasswordHash());
        assertEquals("로컬유저", user.getNickname());
        // 새 계정 생성이 아니라 기존 계정에 방금 연동된 로그인이므로, 성공 핸들러가 안내를
        // 띄울 수 있도록 이 값이 true여야 한다.
        assertTrue(((CustomOAuth2User) result).isLinkedToExistingAccount());
        verify(repository, never()).saveAndFlush(any(User.class));
    }

    @Test
    void linksToExistingAccountWhenOAuthEmailDiffersOnlyByCaseOrWhitespace() {
        UserRepository repository = mock(UserRepository.class);
        CustomOAuth2UserService service =
                new CustomOAuth2UserService(repository, mock(PlatformTransactionManager.class));

        // 로컬 계정은 정규화된(소문자, trim) 이메일로 저장되어 있는데, 구글/카카오가 대소문자가
        // 섞인 원본 이메일을 내려주는 경우 — 정규화 없이 findByEmail을 호출하면 이 계정을 못 찾는다.
        User existingLocalUser = User.createLocalUser("shared@example.com", "encoded-hash", "로컬유저");
        when(repository.findByProviderAndProviderId(AuthProvider.KAKAO, "123")).thenReturn(Optional.empty());
        when(repository.findByEmail("shared@example.com")).thenReturn(Optional.of(existingLocalUser));

        OAuth2User result = service.processOAuth2User(
                "kakao", kakaoOAuth2User(123L, "카카오닉네임", "http://img", "  Shared@Example.com  "));

        User user = ((CustomOAuth2User) result).getUser();
        assertEquals(existingLocalUser, user);
        assertEquals(AuthProvider.KAKAO, user.getProvider());
        assertTrue(((CustomOAuth2User) result).isLinkedToExistingAccount());
        verify(repository, never()).saveAndFlush(any(User.class));
    }

    @Test
    void doesNotLinkOrRecoverToExistingAccountWhenOAuthEmailIsUnverified() {
        UserRepository repository = mock(UserRepository.class);
        CustomOAuth2UserService service =
                new CustomOAuth2UserService(repository, mock(PlatformTransactionManager.class));

        // 이미 다른 계정이 이 이메일로 가입되어 있는 상태를 가정한다.
        User existingUser = User.createLocalUser("shared@example.com", "encoded-hash", "로컬유저");
        when(repository.findByProviderAndProviderId(AuthProvider.KAKAO, "123")).thenReturn(Optional.empty());
        when(repository.findByEmail("shared@example.com")).thenReturn(Optional.of(existingUser));
        // 검증 안 된 이메일로 신규 생성을 시도하다 email unique 제약에 걸리는 상황을 재현한다.
        when(repository.saveAndFlush(any(User.class)))
                .thenThrow(new DataIntegrityViolationException("unique constraint violation"));

        // Kakao가 이 이메일을 검증해주지 않았다면(is_email_verified=false), 남이 먼저 등록해둔
        // 이메일이라도 그 계정에 조용히 로그인시키면 안 된다 — 크래시 없이 명확한 실패로 끝나야 한다.
        assertThrows(OAuth2AuthenticationException.class,
                () -> service.processOAuth2User(
                        "kakao", kakaoOAuth2User(123L, "카카오닉네임", "http://img", "shared@example.com", false)));

        // 실패 처리 과정에서도 기존 계정이 이번 로그인 수단으로 연동되지 않은 채 그대로 남아 있어야 한다.
        assertEquals(AuthProvider.LOCAL, existingUser.getProvider());
    }

    @Test
    void storesNullEmailWhenOAuthEmailIsUnverified() {
        UserRepository repository = mock(UserRepository.class);
        CustomOAuth2UserService service =
                new CustomOAuth2UserService(repository, mock(PlatformTransactionManager.class));

        when(repository.findByProviderAndProviderId(AuthProvider.KAKAO, "123")).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        OAuth2User result = service.processOAuth2User(
                "kakao", kakaoOAuth2User(123L, "카카오닉네임", "http://img", "unverified@example.com", false));

        // 검증 안 된 이메일을 그대로 저장해두면, 나중에 이 이메일의 실제 소유자가 검증된 OAuth로
        // 로그인할 때 findVerifiedEmailMatch가 이 row를 "이미 존재하는 계정"으로 착각해 연동해버려,
        // 검증 안 된 이메일로 미리 만들어둔 계정에 진짜 소유자가 합쳐지는 계정 탈취로 이어질 수 있다.
        // null로 저장하면 findByEmail이 이 row를 절대 찾을 수 없어 그 위험이 원천 차단된다.
        User user = ((CustomOAuth2User) result).getUser();
        assertEquals(null, user.getEmail());
        assertEquals(AuthProvider.KAKAO, user.getProvider());
        assertFalse(((CustomOAuth2User) result).isLinkedToExistingAccount());
    }

    @Test
    void wrapsUnrecoverableEmailConflictAsAuthenticationExceptionInsteadOfCrashing() {
        UserRepository repository = mock(UserRepository.class);
        CustomOAuth2UserService service =
                new CustomOAuth2UserService(repository, mock(PlatformTransactionManager.class));

        when(repository.findByProviderAndProviderId(AuthProvider.KAKAO, "123")).thenReturn(Optional.empty());
        when(repository.findByEmail("test@kakao.com")).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any(User.class)))
                .thenThrow(new DataIntegrityViolationException("unique constraint violation"));

        // 레이스 복구 조회(재조회)에서도 끝내 아무것도 못 찾는 극단적인 경우 — DataIntegrityViolationException을
        // 그대로 흘려보내 서블릿까지 올려 500으로 크래시시키는 대신, OAuth2AuthenticationFailureHandler가
        // 처리할 수 있는 AuthenticationException으로 감싸져야 한다.
        assertThrows(OAuth2AuthenticationException.class,
                () -> service.processOAuth2User("kakao", kakaoOAuth2User(123L, "테스트유저", "http://img", "test@kakao.com")));
    }
}
