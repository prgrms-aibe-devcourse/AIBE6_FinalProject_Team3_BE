package com.algogyeyak.auth.oauth;

import com.algogyeyak.user.entity.AuthProvider;
import com.algogyeyak.user.entity.User;
import com.algogyeyak.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CustomOAuth2UserServiceTest {

    private static OAuth2User kakaoOAuth2User(long id, String nickname, String profileImageUrl, String email) {
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
        CustomOAuth2UserService service = new CustomOAuth2UserService(repository);

        when(repository.findByProviderAndProviderId(AuthProvider.KAKAO, "123")).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        OAuth2User result = service.processOAuth2User("kakao", kakaoOAuth2User(123L, "테스트유저", "http://img", "test@kakao.com"));

        assertInstanceOf(CustomOAuth2User.class, result);
        User user = ((CustomOAuth2User) result).getUser();
        assertEquals("테스트유저", user.getNickname());
        assertEquals("test@kakao.com", user.getEmail());
        assertEquals(AuthProvider.KAKAO, user.getProvider());
        assertEquals("123", user.getProviderId());

        verify(repository).saveAndFlush(any(User.class));
    }

    @Test
    void reusesExistingUserWithoutOverwritingCustomizedProfile() {
        UserRepository repository = mock(UserRepository.class);
        CustomOAuth2UserService service = new CustomOAuth2UserService(repository);

        // 로그인 이후 프로필 등록/수정 화면에서 닉네임과 사진을 직접 바꾼 상태를 가정한다.
        User existing = User.createOAuthUser("old@kakao.com", "커스텀닉네임", "http://custom", AuthProvider.KAKAO, "123");
        when(repository.findByProviderAndProviderId(AuthProvider.KAKAO, "123")).thenReturn(Optional.of(existing));

        OAuth2User result = service.processOAuth2User("kakao", kakaoOAuth2User(123L, "새닉네임", "http://new", "old@kakao.com"));

        // 재로그인 시 OAuth 제공자 값(새닉네임/http://new)이 아니라 기존에 커스터마이징한 값이 그대로 유지되어야 한다.
        User user = ((CustomOAuth2User) result).getUser();
        assertEquals("커스텀닉네임", user.getNickname());
        assertEquals("http://custom", user.getProfileImageUrl());

        verify(repository, never()).saveAndFlush(any(User.class));
    }

    @Test
    void fallsBackToGeneratedNicknameWhenKakaoNicknameMissing() {
        UserRepository repository = mock(UserRepository.class);
        CustomOAuth2UserService service = new CustomOAuth2UserService(repository);

        when(repository.findByProviderAndProviderId(AuthProvider.KAKAO, "999")).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        OAuth2User result = service.processOAuth2User("kakao", kakaoOAuth2User(999L, null, null, null));

        User user = ((CustomOAuth2User) result).getUser();
        assertEquals("kakao_999", user.getNickname());
    }

    @Test
    void reusesWinnerRowWhenConcurrentFirstLoginHitsUniqueConstraint() {
        UserRepository repository = mock(UserRepository.class);
        CustomOAuth2UserService service = new CustomOAuth2UserService(repository);

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
}
