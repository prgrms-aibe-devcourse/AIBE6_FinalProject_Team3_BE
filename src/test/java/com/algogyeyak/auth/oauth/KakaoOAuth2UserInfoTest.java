package com.algogyeyak.auth.oauth;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class KakaoOAuth2UserInfoTest {

    @Test
    void getProviderIdReturnsIdAsString() {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("id", 1234567890L);

        assertEquals("1234567890", new KakaoOAuth2UserInfo(attributes).getProviderId());
    }

    @Test
    void getProviderIdThrowsWhenIdIsMissing() {
        // 회귀 테스트 - String.valueOf(null)이 리터럴 문자열 "null"을 반환해버리면, id가 없는
        // 응답이 반복될 때마다 findByProviderAndProviderId(KAKAO, "null")이 서로 다른 사용자를
        // 같은 계정으로 착각해 매칭시킬 수 있었다. 반드시 예외로 실패해야 한다.
        Map<String, Object> attributes = new HashMap<>();

        assertThrows(OAuth2AuthenticationException.class, () -> new KakaoOAuth2UserInfo(attributes).getProviderId());
    }
}
