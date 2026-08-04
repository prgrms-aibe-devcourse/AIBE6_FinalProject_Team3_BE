package com.algogyeyak.auth.oauth;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OAuth2UserInfoFactoryTest {

    @Test
    void parsesGoogleAttributes() {
        Map<String, Object> attributes = Map.of(
                "sub", "1234567890",
                "email", "test@gmail.com",
                "email_verified", true,
                "name", "Test User",
                "picture", "https://example.com/pic.jpg"
        );

        OAuth2UserInfo userInfo = OAuth2UserInfoFactory.getOAuth2UserInfo("google", attributes);

        assertEquals("1234567890", userInfo.getProviderId());
        assertEquals("test@gmail.com", userInfo.getEmail());
        assertEquals("Test User", userInfo.getNickname());
        assertEquals("https://example.com/pic.jpg", userInfo.getProfileImageUrl());
        assertTrue(userInfo.isEmailVerified());
    }

    @Test
    void googleTreatsUnverifiedOrMissingEmailVerifiedClaimAsUnverified() {
        Map<String, Object> unverified = Map.of("sub", "1", "email", "test@gmail.com", "email_verified", false);
        Map<String, Object> missing = Map.of("sub", "1", "email", "test@gmail.com");

        assertFalse(OAuth2UserInfoFactory.getOAuth2UserInfo("google", unverified).isEmailVerified());
        assertFalse(OAuth2UserInfoFactory.getOAuth2UserInfo("google", missing).isEmailVerified());
    }

    @Test
    void parsesKakaoAttributes() {
        Map<String, Object> profile = Map.of(
                "nickname", "카카오유저",
                "profile_image_url", "https://example.com/kakao.jpg"
        );
        Map<String, Object> kakaoAccount = Map.of(
                "email", "test@kakao.com",
                "is_email_verified", true,
                "profile", profile
        );
        Map<String, Object> attributes = Map.of(
                "id", 987654321L,
                "kakao_account", kakaoAccount
        );

        OAuth2UserInfo userInfo = OAuth2UserInfoFactory.getOAuth2UserInfo("kakao", attributes);

        assertEquals("987654321", userInfo.getProviderId());
        assertEquals("test@kakao.com", userInfo.getEmail());
        assertEquals("카카오유저", userInfo.getNickname());
        assertEquals("https://example.com/kakao.jpg", userInfo.getProfileImageUrl());
        assertTrue(userInfo.isEmailVerified());
    }

    @Test
    void kakaoTreatsUnverifiedOrMissingFlagAsUnverified() {
        Map<String, Object> unverifiedAccount = Map.of("email", "test@kakao.com", "is_email_verified", false);
        Map<String, Object> withUnverifiedAccount = Map.of("id", 1L, "kakao_account", unverifiedAccount);
        Map<String, Object> withoutAccount = Map.of("id", 1L);

        assertFalse(OAuth2UserInfoFactory.getOAuth2UserInfo("kakao", withUnverifiedAccount).isEmailVerified());
        assertFalse(OAuth2UserInfoFactory.getOAuth2UserInfo("kakao", withoutAccount).isEmailVerified());
    }

    @Test
    void kakaoWithoutAccountConsentHasNullEmailAndNickname() {
        Map<String, Object> attributes = Map.of("id", 111L);

        OAuth2UserInfo userInfo = OAuth2UserInfoFactory.getOAuth2UserInfo("kakao", attributes);

        assertEquals("111", userInfo.getProviderId());
        assertEquals(null, userInfo.getEmail());
        assertEquals(null, userInfo.getNickname());
    }

    @Test
    void throwsForUnsupportedProvider() {
        assertThrows(OAuth2AuthenticationException.class,
                () -> OAuth2UserInfoFactory.getOAuth2UserInfo("naver", Map.of()));
    }
}
