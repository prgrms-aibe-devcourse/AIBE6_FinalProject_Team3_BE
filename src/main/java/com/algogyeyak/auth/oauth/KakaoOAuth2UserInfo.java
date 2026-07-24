package com.algogyeyak.auth.oauth;

import java.util.Map;

public class KakaoOAuth2UserInfo extends OAuth2UserInfo {

    public KakaoOAuth2UserInfo(Map<String, Object> attributes) {
        super(attributes);
    }

    @Override
    public String getProviderId() {
        return String.valueOf(attributes.get("id"));
    }

    @Override
    public String getEmail() {
        Object email = getKakaoAccount() != null ? getKakaoAccount().get("email") : null;
        return email != null ? String.valueOf(email) : null;
    }

    @Override
    public String getNickname() {
        Object nickname = getProfile() != null ? getProfile().get("nickname") : null;
        return nickname != null ? String.valueOf(nickname) : null;
    }

    @Override
    public String getProfileImageUrl() {
        Object profileImageUrl = getProfile() != null ? getProfile().get("profile_image_url") : null;
        return profileImageUrl != null ? String.valueOf(profileImageUrl) : null;
    }

    // 카카오계정 이메일 인증 여부(is_email_verified) — 이메일 동의 자체를 안 받았거나(kakao_account
    // 없음), 인증 전 계정이면 false로 취급한다.
    @Override
    public boolean isEmailVerified() {
        Object verified = getKakaoAccount() != null ? getKakaoAccount().get("is_email_verified") : null;
        return Boolean.parseBoolean(String.valueOf(verified));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getKakaoAccount() {
        return (Map<String, Object>) attributes.get("kakao_account");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getProfile() {
        Map<String, Object> kakaoAccount = getKakaoAccount();
        return kakaoAccount != null ? (Map<String, Object>) kakaoAccount.get("profile") : null;
    }
}
