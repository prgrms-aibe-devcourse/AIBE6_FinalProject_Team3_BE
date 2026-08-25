package com.algogyeyak.auth.oauth;

import com.algogyeyak.user.entity.User;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.user.OAuth2User;

import java.util.List;
import java.util.Map;

public class CustomOAuth2User implements OAuth2User {

    private final User user;
    private final Map<String, Object> attributes;
    private final boolean linkedToExistingAccount;

    // linkedToExistingAccount: 이번 로그인이 새 계정 생성이 아니라, 같은(검증된) 이메일의 기존
    // 계정(로컬 가입 또는 다른 소셜)에 방금 연동된 경우 true — 성공 핸들러가 이 값을 보고 프론트에
    // "기존 계정과 연결되었습니다" 안내를 한 번 띄울지 판단한다.
    public CustomOAuth2User(User user, Map<String, Object> attributes, boolean linkedToExistingAccount) {
        this.user = user;
        this.attributes = attributes;
        this.linkedToExistingAccount = linkedToExistingAccount;
    }

    public User getUser() {
        return user;
    }

    public boolean isLinkedToExistingAccount() {
        return linkedToExistingAccount;
    }

    @Override
    public Map<String, Object> getAttributes() {
        return attributes;
    }

    @Override
    public List<GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()));
    }

    @Override
    public String getName() {
        return String.valueOf(user.getId());
    }
}
