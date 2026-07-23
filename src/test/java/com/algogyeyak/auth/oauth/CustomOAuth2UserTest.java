package com.algogyeyak.auth.oauth;

import com.algogyeyak.user.entity.User;
import com.algogyeyak.user.enums.AuthProvider;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CustomOAuth2UserTest {

    @Test
    void exposesUnderlyingUserAndAttributes() {
        User user = User.createOAuthUser("test@example.com", "테스트유저", "http://img", AuthProvider.KAKAO, "123");
        ReflectionTestUtils.setField(user, "id", 1L);
        Map<String, Object> attributes = Map.of("id", "123");

        CustomOAuth2User customUser = new CustomOAuth2User(user, attributes);

        assertEquals(user, customUser.getUser());
        assertEquals(attributes, customUser.getAttributes());
        assertEquals("1", customUser.getName());
    }

    @Test
    void grantsAuthorityBasedOnUserRole() {
        User user = User.createOAuthUser("test@example.com", "테스트유저", "http://img", AuthProvider.KAKAO, "123");
        ReflectionTestUtils.setField(user, "id", 1L);

        CustomOAuth2User customUser = new CustomOAuth2User(user, Map.of());

        assertTrue(customUser.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch("ROLE_USER"::equals));
    }
}
