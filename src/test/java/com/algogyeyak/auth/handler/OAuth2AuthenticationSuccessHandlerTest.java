package com.algogyeyak.auth.handler;

import com.algogyeyak.auth.jwt.JwtAuthenticationFilter;
import com.algogyeyak.auth.jwt.JwtProvider;
import com.algogyeyak.auth.oauth.CookieAuthorizationRequestRepository;
import com.algogyeyak.auth.oauth.CookieUtils;
import com.algogyeyak.auth.oauth.CustomOAuth2User;
import com.algogyeyak.user.entity.User;
import com.algogyeyak.user.enums.AuthProvider;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class OAuth2AuthenticationSuccessHandlerTest {

    private final JwtProvider jwtProvider =
            new JwtProvider("test-secret-key-must-be-at-least-32-bytes-long", 3600);
    private final CookieAuthorizationRequestRepository authorizationRequestRepository =
            mock(CookieAuthorizationRequestRepository.class);
    private final CookieUtils cookieUtils = mock(CookieUtils.class);
    private final OAuth2AuthenticationSuccessHandler handler =
            new OAuth2AuthenticationSuccessHandler(jwtProvider, authorizationRequestRepository, cookieUtils);

    private Authentication authenticationFor(Long userId) {
        User user = User.createOAuthUser("test@example.com", "테스트유저", "http://img", AuthProvider.KAKAO, "123");
        ReflectionTestUtils.setField(user, "id", userId);
        CustomOAuth2User principal = new CustomOAuth2User(user, Map.of("id", "123"));
        return new UsernamePasswordAuthenticationToken(principal, null);
    }

    @Test
    void issuesAccessTokenCookieContainingUserClaims() throws Exception {
        ReflectionTestUtils.setField(handler, "authorizedRedirectUri", "https://example.com/login/success");
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationSuccess(request, response, authenticationFor(42L));

        ArgumentCaptor<String> tokenCaptor = ArgumentCaptor.forClass(String.class);
        verify(cookieUtils).addCookie(
                eq(response), eq(JwtAuthenticationFilter.ACCESS_TOKEN_COOKIE_NAME), tokenCaptor.capture(), eq(3600));

        Claims claims = jwtProvider.parseClaims(tokenCaptor.getValue());
        assertEquals("42", claims.getSubject());
        assertEquals("test@example.com", claims.get("email", String.class));
        assertEquals("USER", claims.get("role", String.class));
    }

    @Test
    void clearsAuthorizationRequestCookieAndRedirectsToConfiguredUri() throws Exception {
        ReflectionTestUtils.setField(handler, "authorizedRedirectUri", "https://example.com/login/success");
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationSuccess(request, response, authenticationFor(1L));

        verify(authorizationRequestRepository).removeAuthorizationRequest(request, response);
        assertEquals("https://example.com/login/success", response.getRedirectedUrl());
    }
}
