package com.algogyeyak.auth.oauth;

import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;

import java.util.Map;

public class GoogleOAuth2UserInfo extends OAuth2UserInfo {

    public GoogleOAuth2UserInfo(Map<String, Object> attributes) {
        super(attributes);
    }

    @Override
    public String getProviderId() {
        // String.valueOf(null)은 "null"이라는 실제 문자열을 반환한다 - sub가 없는(제공자 응답
        // 오류/장애) 경우를 조용히 그 문자열로 바꿔치기하면, 같은 결함이 다른 사용자에게도
        // 반복될 때 findByProviderAndProviderId(GOOGLE, "null")이 서로 다른 사용자를 같은
        // 계정으로 착각해 매칭시킬 수 있다 - 반드시 실패시킨다.
        Object sub = attributes.get("sub");
        if (sub == null) {
            throw new OAuth2AuthenticationException(new OAuth2Error("oauth_login_failed", "Google 인증 응답에 sub(고유 식별자)가 없습니다.", null));
        }
        return String.valueOf(sub);
    }

    @Override
    public String getEmail() {
        return (String) attributes.get("email");
    }

    @Override
    public String getNickname() {
        return (String) attributes.get("name");
    }

    @Override
    public String getProfileImageUrl() {
        return (String) attributes.get("picture");
    }

    // 구글 OIDC 표준 클레임. Jackson이 JSON boolean을 Boolean으로 파싱하므로 보통 Boolean이지만,
    // 혹시 문자열로 오더라도 안전하게 처리하기 위해 String.valueOf를 거쳐 파싱한다.
    @Override
    public boolean isEmailVerified() {
        return Boolean.parseBoolean(String.valueOf(attributes.get("email_verified")));
    }
}
