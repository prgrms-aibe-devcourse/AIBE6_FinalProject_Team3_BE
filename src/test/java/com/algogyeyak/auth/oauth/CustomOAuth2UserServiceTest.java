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
        when(repository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        OAuth2User result = service.processOAuth2User("kakao", kakaoOAuth2User(123L, "테스트유저", "http://img", "test@kakao.com"));

        assertInstanceOf(CustomOAuth2User.class, result);
        User user = ((CustomOAuth2User) result).getUser();
        assertEquals("테스트유저", user.getNickname());
        assertEquals("test@kakao.com", user.getEmail());
        assertEquals(AuthProvider.KAKAO, user.getProvider());
        assertEquals("123", user.getProviderId());

        verify(repository).save(any(User.class));
    }

    @Test
    void reusesExistingUserAndUpdatesProfileWithoutSaving() {
        UserRepository repository = mock(UserRepository.class);
        CustomOAuth2UserService service = new CustomOAuth2UserService(repository);

        User existing = User.createOAuthUser("old@kakao.com", "옛날닉네임", "http://old", AuthProvider.KAKAO, "123");
        when(repository.findByProviderAndProviderId(AuthProvider.KAKAO, "123")).thenReturn(Optional.of(existing));

        OAuth2User result = service.processOAuth2User("kakao", kakaoOAuth2User(123L, "새닉네임", "http://new", "old@kakao.com"));

        User user = ((CustomOAuth2User) result).getUser();
        assertEquals("새닉네임", user.getNickname());
        assertEquals("http://new", user.getProfileImageUrl());

        verify(repository, never()).save(any(User.class));
    }

    @Test
    void fallsBackToGeneratedNicknameWhenKakaoNicknameMissing() {
        UserRepository repository = mock(UserRepository.class);
        CustomOAuth2UserService service = new CustomOAuth2UserService(repository);

        when(repository.findByProviderAndProviderId(AuthProvider.KAKAO, "999")).thenReturn(Optional.empty());
        when(repository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        OAuth2User result = service.processOAuth2User("kakao", kakaoOAuth2User(999L, null, null, null));

        User user = ((CustomOAuth2User) result).getUser();
        assertEquals("kakao_999", user.getNickname());
    }
}
