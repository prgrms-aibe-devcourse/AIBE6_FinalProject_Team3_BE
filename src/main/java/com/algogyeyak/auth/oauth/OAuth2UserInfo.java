package com.algogyeyak.auth.oauth;

import java.util.Map;

public abstract class OAuth2UserInfo {

    protected final Map<String, Object> attributes;

    protected OAuth2UserInfo(Map<String, Object> attributes) {
        this.attributes = attributes;
    }

    public abstract String getProviderId();

    public abstract String getEmail();

    public abstract String getNickname();

    public abstract String getProfileImageUrl();

    /**
     * 이 provider가 이메일 소유권을 검증해줬는지 여부. 계정 자동 연동(같은 이메일이면 기존 계정에
     * 연결)의 유일한 안전 근거이므로, 검증되지 않은 이메일은 절대 자동 연동에 사용하면 안 된다 —
     * {@link com.algogyeyak.auth.oauth.CustomOAuth2UserService}에서 이 값을 확인한다.
     */
    public abstract boolean isEmailVerified();
}
