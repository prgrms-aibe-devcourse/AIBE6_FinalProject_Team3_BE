package com.algogyeyak.auth.oauth;

import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;

import java.util.Map;

public class KakaoOAuth2UserInfo extends OAuth2UserInfo {

    public KakaoOAuth2UserInfo(Map<String, Object> attributes) {
        super(attributes);
    }

    @Override
    public String getProviderId() {
        // String.valueOf(null)은 "null"이라는 실제 문자열을 반환한다 - id가 없는(제공자 응답
        // 오류/장애) 경우를 조용히 그 문자열로 바꿔치기하면, 같은 결함이 다른 사용자에게도
        // 반복될 때 findByProviderAndProviderId(KAKAO, "null")이 서로 다른 사용자를 같은
        // 계정으로 착각해 매칭시킬 수 있다 - 반드시 실패시킨다.
        Object id = attributes.get("id");
        if (id == null) {
            throw new OAuth2AuthenticationException(new OAuth2Error("oauth_login_failed", "카카오 인증 응답에 id(고유 식별자)가 없습니다.", null));
        }
        return String.valueOf(id);
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
