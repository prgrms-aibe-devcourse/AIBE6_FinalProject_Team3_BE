package com.algogyeyak.auth.oauth;

import java.util.Map;

public class GoogleOAuth2UserInfo extends OAuth2UserInfo {

    public GoogleOAuth2UserInfo(Map<String, Object> attributes) {
        super(attributes);
    }

    @Override
    public String getProviderId() {
        return String.valueOf(attributes.get("sub"));
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
