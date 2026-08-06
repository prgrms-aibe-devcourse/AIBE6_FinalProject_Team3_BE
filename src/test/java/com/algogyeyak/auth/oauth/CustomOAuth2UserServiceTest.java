package com.algogyeyak.auth.oauth;

import com.algogyeyak.user.enums.AuthProvider;
import com.algogyeyak.user.entity.User;
import com.algogyeyak.user.entity.UserSocialAccount;
import com.algogyeyak.user.repository.UserRepository;
import com.algogyeyak.user.repository.UserSocialAccountRepository;
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

    private static CustomOAuth2UserService service(UserRepository userRepository, UserSocialAccountRepository socialAccountRepository) {
        return new CustomOAuth2UserService(userRepository, socialAccountRepository, mock(PlatformTransactionManager.class));
    }

    @Test
    void createsNewUserWhenNoExistingAccountForProvider() {
        UserRepository repository = mock(UserRepository.class);
        UserSocialAccountRepository socialAccountRepository = mock(UserSocialAccountRepository.class);
        CustomOAuth2UserService service = service(repository, socialAccountRepository);

        when(socialAccountRepository.findByProviderAndProviderId(AuthProvider.KAKAO, "123")).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(socialAccountRepository.saveAndFlush(any(UserSocialAccount.class))).thenAnswer(invocation -> invocation.getArgument(0));

        OAuth2User result = service.processOAuth2User("kakao", kakaoOAuth2User(123L, "테스트유저", "http://img", "test@kakao.com"));

        assertInstanceOf(CustomOAuth2User.class, result);
        User user = ((CustomOAuth2User) result).getUser();
        assertEquals("테스트유저", user.getNickname());
        assertEquals("test@kakao.com", user.getEmail());
        assertFalse(((CustomOAuth2User) result).isLinkedToExistingAccount());

        verify(repository).saveAndFlush(any(User.class));
        // 최초 가입이므로 이 provider에 대한 UserSocialAccount도 함께 만들어져야 한다.
        verify(socialAccountRepository).saveAndFlush(any(UserSocialAccount.class));
    }

    @Test
    void reusesExistingUserWithoutOverwritingCustomizedProfile() {
        UserRepository repository = mock(UserRepository.class);
        UserSocialAccountRepository socialAccountRepository = mock(UserSocialAccountRepository.class);
        CustomOAuth2UserService service = service(repository, socialAccountRepository);

        // 로그인 이후 프로필 등록/수정 화면에서 닉네임과 사진을 직접 바꾼 상태를 가정한다.
        User existing = User.createOAuthUser("old@kakao.com", "커스텀닉네임", "http://custom");
        UserSocialAccount existingSocialAccount = UserSocialAccount.of(existing, AuthProvider.KAKAO, "123");
        when(socialAccountRepository.findByProviderAndProviderId(AuthProvider.KAKAO, "123"))
                .thenReturn(Optional.of(existingSocialAccount));

        OAuth2User result = service.processOAuth2User("kakao", kakaoOAuth2User(123L, "새닉네임", "http://new", "old@kakao.com"));

        // 재로그인 시 OAuth 제공자 값(새닉네임/http://new)이 아니라 기존에 커스터마이징한 값이 그대로 유지되어야 한다.
        User user = ((CustomOAuth2User) result).getUser();
        assertEquals("커스텀닉네임", user.getNickname());
        assertEquals("http://custom", user.getProfileImageUrl());
        // provider+providerId로 바로 찾은 기존 회원의 재로그인일 뿐, 방금 새로 연동된 게 아니다.
        assertFalse(((CustomOAuth2User) result).isLinkedToExistingAccount());

        verify(repository, never()).saveAndFlush(any(User.class));
        verify(socialAccountRepository, never()).saveAndFlush(any(UserSocialAccount.class));
    }

    @Test
    void fallsBackToGeneratedNicknameWhenKakaoNicknameMissing() {
        UserRepository repository = mock(UserRepository.class);
        UserSocialAccountRepository socialAccountRepository = mock(UserSocialAccountRepository.class);
        CustomOAuth2UserService service = service(repository, socialAccountRepository);

        when(socialAccountRepository.findByProviderAndProviderId(AuthProvider.KAKAO, "999")).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(socialAccountRepository.saveAndFlush(any(UserSocialAccount.class))).thenAnswer(invocation -> invocation.getArgument(0));

        OAuth2User result = service.processOAuth2User("kakao", kakaoOAuth2User(999L, null, null, null));

        User user = ((CustomOAuth2User) result).getUser();
        assertEquals("kakao_999", user.getNickname());
    }

    @Test
    void reusesWinnerRowWhenConcurrentFirstLoginHitsUniqueConstraint() {
        UserRepository repository = mock(UserRepository.class);
        UserSocialAccountRepository socialAccountRepository = mock(UserSocialAccountRepository.class);
        CustomOAuth2UserService service = service(repository, socialAccountRepository);

        User winner = User.createOAuthUser("test@kakao.com", "테스트유저", "http://img");
        UserSocialAccount winnerSocialAccount = UserSocialAccount.of(winner, AuthProvider.KAKAO, "123");

        // 첫 조회 시점엔 아직 아무도 없다고 나오지만(레이스), save 시도 시 다른 스레드가 먼저 커밋해서 유니크 제약 위반이 난다.
        when(socialAccountRepository.findByProviderAndProviderId(AuthProvider.KAKAO, "123"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(winnerSocialAccount));
        when(repository.saveAndFlush(any(User.class)))
                .thenThrow(new DataIntegrityViolationException("unique constraint violation"));

        OAuth2User result = service.processOAuth2User("kakao", kakaoOAuth2User(123L, "테스트유저", "http://img", "test@kakao.com"));

        User user = ((CustomOAuth2User) result).getUser();
        assertEquals(winner, user);
    }

    @Test
    void linksNewProviderToExistingAccountWithSameEmailInsteadOfCreatingRow() {
        UserRepository repository = mock(UserRepository.class);
        UserSocialAccountRepository socialAccountRepository = mock(UserSocialAccountRepository.class);
        CustomOAuth2UserService service = service(repository, socialAccountRepository);

        // 로컬 이메일/비밀번호로 이미 가입되어 있고, 이번이 처음 하는 구글/카카오 로그인이라고 가정한다.
        User existingLocalUser = User.createLocalUser("shared@example.com", "encoded-hash", "로컬유저");
        when(socialAccountRepository.findByProviderAndProviderId(AuthProvider.KAKAO, "123")).thenReturn(Optional.empty());
        when(repository.findByEmail("shared@example.com")).thenReturn(Optional.of(existingLocalUser));
        when(socialAccountRepository.saveAndFlush(any(UserSocialAccount.class))).thenAnswer(invocation -> invocation.getArgument(0));

        OAuth2User result = service.processOAuth2User("kakao", kakaoOAuth2User(123L, "카카오닉네임", "http://img", "shared@example.com"));

        User user = ((CustomOAuth2User) result).getUser();
        assertEquals(existingLocalUser, user);
        // 로컬 로그인이 계속 가능하도록 이메일/비밀번호 해시는 그대로 유지되어야 한다.
        assertEquals("encoded-hash", user.getPasswordHash());
        assertEquals("로컬유저", user.getNickname());
        // 새 계정 생성이 아니라 기존 계정에 방금 연동된 로그인이므로, 성공 핸들러가 안내를
        // 띄울 수 있도록 이 값이 true여야 한다.
        assertTrue(((CustomOAuth2User) result).isLinkedToExistingAccount());
        verify(repository, never()).saveAndFlush(any(User.class));
        // 로컬 계정에 카카오가 처음 연동되는 것이므로 새 UserSocialAccount가 만들어져야 한다.
        verify(socialAccountRepository).saveAndFlush(any(UserSocialAccount.class));
    }

    @Test
    void linksToExistingAccountWhenOAuthEmailDiffersOnlyByCaseOrWhitespace() {
        UserRepository repository = mock(UserRepository.class);
        UserSocialAccountRepository socialAccountRepository = mock(UserSocialAccountRepository.class);
        CustomOAuth2UserService service = service(repository, socialAccountRepository);

        // 로컬 계정은 정규화된(소문자, trim) 이메일로 저장되어 있는데, 구글/카카오가 대소문자가
        // 섞인 원본 이메일을 내려주는 경우 — 정규화 없이 findByEmail을 호출하면 이 계정을 못 찾는다.
        User existingLocalUser = User.createLocalUser("shared@example.com", "encoded-hash", "로컬유저");
        when(socialAccountRepository.findByProviderAndProviderId(AuthProvider.KAKAO, "123")).thenReturn(Optional.empty());
        when(repository.findByEmail("shared@example.com")).thenReturn(Optional.of(existingLocalUser));
        when(socialAccountRepository.saveAndFlush(any(UserSocialAccount.class))).thenAnswer(invocation -> invocation.getArgument(0));

        OAuth2User result = service.processOAuth2User(
                "kakao", kakaoOAuth2User(123L, "카카오닉네임", "http://img", "  Shared@Example.com  "));

        User user = ((CustomOAuth2User) result).getUser();
        assertEquals(existingLocalUser, user);
        assertTrue(((CustomOAuth2User) result).isLinkedToExistingAccount());
        verify(repository, never()).saveAndFlush(any(User.class));
    }

    @Test
    void doesNotLinkOrRecoverToExistingAccountWhenOAuthEmailIsUnverified() {
        UserRepository repository = mock(UserRepository.class);
        UserSocialAccountRepository socialAccountRepository = mock(UserSocialAccountRepository.class);
        CustomOAuth2UserService service = service(repository, socialAccountRepository);

        // 이미 다른 계정이 이 이메일로 가입되어 있는 상태를 가정한다.
        User existingUser = User.createLocalUser("shared@example.com", "encoded-hash", "로컬유저");
        when(socialAccountRepository.findByProviderAndProviderId(AuthProvider.KAKAO, "123")).thenReturn(Optional.empty());
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
        verify(socialAccountRepository, never()).saveAndFlush(any(UserSocialAccount.class));
    }

    @Test
    void storesNullEmailWhenOAuthEmailIsUnverified() {
        UserRepository repository = mock(UserRepository.class);
        UserSocialAccountRepository socialAccountRepository = mock(UserSocialAccountRepository.class);
        CustomOAuth2UserService service = service(repository, socialAccountRepository);

        when(socialAccountRepository.findByProviderAndProviderId(AuthProvider.KAKAO, "123")).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(socialAccountRepository.saveAndFlush(any(UserSocialAccount.class))).thenAnswer(invocation -> invocation.getArgument(0));

        OAuth2User result = service.processOAuth2User(
                "kakao", kakaoOAuth2User(123L, "카카오닉네임", "http://img", "unverified@example.com", false));

        // 검증 안 된 이메일을 그대로 저장해두면, 나중에 이 이메일의 실제 소유자가 검증된 OAuth로
        // 로그인할 때 findVerifiedEmailMatch가 이 row를 "이미 존재하는 계정"으로 착각해 연동해버려,
        // 검증 안 된 이메일로 미리 만들어둔 계정에 진짜 소유자가 합쳐지는 계정 탈취로 이어질 수 있다.
        // null로 저장하면 findByEmail이 이 row를 절대 찾을 수 없어 그 위험이 원천 차단된다.
        User user = ((CustomOAuth2User) result).getUser();
        assertEquals(null, user.getEmail());
        assertFalse(((CustomOAuth2User) result).isLinkedToExistingAccount());
    }

    @Test
    void wrapsUnrecoverableEmailConflictAsAuthenticationExceptionInsteadOfCrashing() {
        UserRepository repository = mock(UserRepository.class);
        UserSocialAccountRepository socialAccountRepository = mock(UserSocialAccountRepository.class);
        CustomOAuth2UserService service = service(repository, socialAccountRepository);

        when(socialAccountRepository.findByProviderAndProviderId(AuthProvider.KAKAO, "123")).thenReturn(Optional.empty());
        when(repository.findByEmail("test@kakao.com")).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any(User.class)))
                .thenThrow(new DataIntegrityViolationException("unique constraint violation"));

        // 레이스 복구 조회(재조회)에서도 끝내 아무것도 못 찾는 극단적인 경우 — DataIntegrityViolationException을
        // 그대로 흘려보내 서블릿까지 올려 500으로 크래시시키는 대신, OAuth2AuthenticationFailureHandler가
        // 처리할 수 있는 AuthenticationException으로 감싸져야 한다.
        assertThrows(OAuth2AuthenticationException.class,
                () -> service.processOAuth2User("kakao", kakaoOAuth2User(123L, "테스트유저", "http://img", "test@kakao.com")));
    }

    @Test
    void recoversWithFallbackNicknameWhenTheOnlyConflictIsNickname() {
        UserRepository repository = mock(UserRepository.class);
        UserSocialAccountRepository socialAccountRepository = mock(UserSocialAccountRepository.class);
        CustomOAuth2UserService service = service(repository, socialAccountRepository);

        when(socialAccountRepository.findByProviderAndProviderId(AuthProvider.KAKAO, "123")).thenReturn(Optional.empty());
        when(repository.findByEmail("test@kakao.com")).thenReturn(Optional.empty());
        // 첫 시도(제공자가 내려준 닉네임)는 전혀 무관한 다른 유저의 닉네임과 우연히 겹쳐 유니크 제약
        // 위반이 나고, 재시도(provider+providerId 기반 fallback 닉네임)는 성공한다고 가정한다.
        when(repository.existsByNickname("테스트유저")).thenReturn(true);
        when(repository.saveAndFlush(any(User.class)))
                .thenThrow(new DataIntegrityViolationException("unique constraint violation"))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(socialAccountRepository.saveAndFlush(any(UserSocialAccount.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        // 예전엔 provider+providerId도 이메일도 못 찾으면 원인과 무관하게 항상 email_conflict로
        // 영구 실패했다 - 실제 원인이 닉네임 충돌이면 이제 유일한 fallback 닉네임으로 자동
        // 재시도해서 정상적으로 가입돼야 한다(OAuth 가입은 로컬 가입과 달리 유저가 직접 다른
        // 닉네임을 골라 재시도할 방법이 없으므로).
        OAuth2User result =
                service.processOAuth2User("kakao", kakaoOAuth2User(123L, "테스트유저", "http://img", "test@kakao.com"));

        User user = ((CustomOAuth2User) result).getUser();
        assertEquals("kakao_123", user.getNickname());
        assertEquals("test@kakao.com", user.getEmail());
    }

    // --- 다중 소셜 연동(user_social_accounts) 전용 시나리오 ---

    @Test
    void reLoginThroughAlreadyLinkedSecondProviderFindsSameUserWithoutRelinking() {
        UserRepository repository = mock(UserRepository.class);
        UserSocialAccountRepository socialAccountRepository = mock(UserSocialAccountRepository.class);
        CustomOAuth2UserService service = service(repository, socialAccountRepository);

        // 구글로 가입했고, 이후 카카오도 연동해둔 유저가 다시 구글로 로그인하는 상황.
        User user = User.createOAuthUser("test@example.com", "테스트유저", "http://img");
        UserSocialAccount googleAccount = UserSocialAccount.of(user, AuthProvider.GOOGLE, "google-1");
        when(socialAccountRepository.findByProviderAndProviderId(AuthProvider.GOOGLE, "google-1"))
                .thenReturn(Optional.of(googleAccount));

        Map<String, Object> attributes = new HashMap<>();
        attributes.put("sub", "google-1");
        attributes.put("email", "test@example.com");
        attributes.put("email_verified", true);
        attributes.put("name", "테스트유저");
        OAuth2User googleUser = new DefaultOAuth2User(
                List.of(new SimpleGrantedAuthority("ROLE_USER")), attributes, "sub");

        OAuth2User result = service.processOAuth2User("google", googleUser);

        // 이미 연동돼 있던 구글로 다시 로그인한 것뿐이라 새 연동(linked=true)이 아니다.
        assertFalse(((CustomOAuth2User) result).isLinkedToExistingAccount());
        assertEquals(user, ((CustomOAuth2User) result).getUser());
        // 이미 존재하는 연동이므로 새 UserSocialAccount를 만들 필요가 없다.
        verify(socialAccountRepository, never()).saveAndFlush(any(UserSocialAccount.class));
        verify(repository, never()).saveAndFlush(any(User.class));
    }

    @Test
    void rejectsLoginWhenExistingSocialAccountUserHasBeenSuspended() {
        UserRepository repository = mock(UserRepository.class);
        UserSocialAccountRepository socialAccountRepository = mock(UserSocialAccountRepository.class);
        CustomOAuth2UserService service = service(repository, socialAccountRepository);

        // 로컬 로그인/refresh는 이미 정지 계정을 거부하는데, 소셜 로그인만 이 검사가 빠져 있으면
        // 관리자가 정지시킨 유저가 소셜 로그인으로 계속 새 세션을 받을 수 있었다.
        User suspended = User.createOAuthUser("test@kakao.com", "테스트유저", "http://img");
        suspended.suspend();
        UserSocialAccount socialAccount = UserSocialAccount.of(suspended, AuthProvider.KAKAO, "123");
        when(socialAccountRepository.findByProviderAndProviderId(AuthProvider.KAKAO, "123"))
                .thenReturn(Optional.of(socialAccount));

        assertThrows(OAuth2AuthenticationException.class,
                () -> service.processOAuth2User(
                        "kakao", kakaoOAuth2User(123L, "테스트유저", "http://img", "test@kakao.com")));
    }

    @Test
    void rejectsLoginWhenWinnerRecoveredFromConcurrentRaceHasBeenSuspended() {
        UserRepository repository = mock(UserRepository.class);
        UserSocialAccountRepository socialAccountRepository = mock(UserSocialAccountRepository.class);
        CustomOAuth2UserService service = service(repository, socialAccountRepository);

        // createUser()가 유니크 제약 위반 후 findByProviderAndProviderId()/findVerifiedEmailMatch()로
        // 복구하는 winner 경로에도 rejectIfBlocked()가 빠져 있었다 - 동시 레이스에서 복구된 계정이
        // 정지 상태여도 그대로 로그인되던 구멍의 회귀 테스트.
        User winner = User.createOAuthUser("test@kakao.com", "테스트유저", "http://img");
        winner.suspend();
        UserSocialAccount winnerSocialAccount = UserSocialAccount.of(winner, AuthProvider.KAKAO, "123");

        when(socialAccountRepository.findByProviderAndProviderId(AuthProvider.KAKAO, "123"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(winnerSocialAccount));
        when(repository.saveAndFlush(any(User.class)))
                .thenThrow(new DataIntegrityViolationException("unique constraint violation"));

        assertThrows(OAuth2AuthenticationException.class,
                () -> service.processOAuth2User(
                        "kakao", kakaoOAuth2User(123L, "테스트유저", "http://img", "test@kakao.com")));
    }

    @Test
    void rejectsLoginAndDoesNotLinkWhenExistingAccountFoundByEmailHasWithdrawn() {
        UserRepository repository = mock(UserRepository.class);
        UserSocialAccountRepository socialAccountRepository = mock(UserSocialAccountRepository.class);
        CustomOAuth2UserService service = service(repository, socialAccountRepository);

        // 로컬로 가입했다가 탈퇴한 계정에, 탈퇴 후 처음 시도하는 소셜 로그인이 이메일 매칭으로
        // 조용히 연동/로그인되면 안 된다.
        User withdrawn = User.createLocalUser("shared@example.com", "encoded-hash", "로컬유저");
        withdrawn.withdraw();
        when(socialAccountRepository.findByProviderAndProviderId(AuthProvider.KAKAO, "123")).thenReturn(Optional.empty());
        when(repository.findByEmail("shared@example.com")).thenReturn(Optional.of(withdrawn));

        assertThrows(OAuth2AuthenticationException.class,
                () -> service.processOAuth2User(
                        "kakao", kakaoOAuth2User(123L, "카카오닉네임", "http://img", "shared@example.com")));

        verify(socialAccountRepository, never()).saveAndFlush(any(UserSocialAccount.class));
    }

    @Test
    void linkingSecondProviderKeepsFirstProviderStillUsable() {
        UserRepository repository = mock(UserRepository.class);
        UserSocialAccountRepository socialAccountRepository = mock(UserSocialAccountRepository.class);
        CustomOAuth2UserService service = service(repository, socialAccountRepository);

        // 구글로 이미 가입돼 있는 유저가 처음으로 카카오도 연동하는 상황.
        User existingGoogleUser =
                User.createOAuthUser("shared@example.com", "구글유저", "http://img");
        when(socialAccountRepository.findByProviderAndProviderId(AuthProvider.KAKAO, "kakao-1")).thenReturn(Optional.empty());
        when(repository.findByEmail("shared@example.com")).thenReturn(Optional.of(existingGoogleUser));
        when(socialAccountRepository.saveAndFlush(any(UserSocialAccount.class))).thenAnswer(invocation -> invocation.getArgument(0));

        OAuth2User result = service.processOAuth2User(
                "kakao", kakaoOAuth2User(1L, "카카오유저", "http://kakao-img", "shared@example.com"));

        User user = ((CustomOAuth2User) result).getUser();
        assertEquals(existingGoogleUser, user);
        assertTrue(((CustomOAuth2User) result).isLinkedToExistingAccount());
        // 구글 연동 자체가 사라진 건 아니다 — 이 유저가 다시 구글로 로그인하면 여전히 같은 계정을
        // 찾을 수 있어야 한다는 게 다중 연동의 핵심이므로, 새 UserSocialAccount(카카오)가 추가로
        // 만들어졌는지만 확인한다(구글 row는 처음부터 건드리지 않았다).
        verify(socialAccountRepository).saveAndFlush(any(UserSocialAccount.class));
        verify(repository, never()).saveAndFlush(any(User.class));
    }
}
