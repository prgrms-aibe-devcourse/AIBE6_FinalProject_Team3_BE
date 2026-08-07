package com.algogyeyak.auth.handler;

import com.algogyeyak.auth.oauth.CookieAuthorizationRequestRepository;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class OAuth2AuthenticationFailureHandlerTest {

    private final CookieAuthorizationRequestRepository authorizationRequestRepository =
            mock(CookieAuthorizationRequestRepository.class);
    private final OAuth2AuthenticationFailureHandler handler =
            new OAuth2AuthenticationFailureHandler(authorizationRequestRepository);

    @Test
    void clearsAuthorizationRequestCookieAndRedirectsWithGenericErrorCode() throws Exception {
        ReflectionTestUtils.setField(handler, "authorizedRedirectUri", "https://example.com/login");
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        AuthenticationException exception = new OAuth2AuthenticationException(new OAuth2Error("access_denied"));

        handler.onAuthenticationFailure(request, response, exception);

        verify(authorizationRequestRepository).removeAuthorizationRequest(request, response);
        assertEquals("https://example.com/login?error=oauth_login_failed", response.getRedirectedUrl());
    }

    @Test
    void forwardsAccountBlockedErrorCodeInsteadOfGenericOne() throws Exception {
        // CustomOAuth2UserService.rejectIfBlocked()가 만드는 account_blocked를 이 핸들러가 전부
        // oauth_login_failed로 뭉개면, frontend(login/page.tsx)가 이미 준비해둔 "정지된 계정입니다"
        // 안내가 화면까지 전혀 도달하지 못한다.
        ReflectionTestUtils.setField(handler, "authorizedRedirectUri", "https://example.com/login");
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        AuthenticationException exception = new OAuth2AuthenticationException(new OAuth2Error("account_blocked"));

        handler.onAuthenticationFailure(request, response, exception);

        assertEquals("https://example.com/login?error=account_blocked", response.getRedirectedUrl());
    }

    @Test
    void forwardsEmailConflictErrorCodeInsteadOfGenericOne() throws Exception {
        // CustomOAuth2UserService.createUser()의 복구 실패 분기가 만드는 email_conflict도
        // 마찬가지로 화면까지 그대로 전달돼야 한다.
        ReflectionTestUtils.setField(handler, "authorizedRedirectUri", "https://example.com/login");
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        AuthenticationException exception = new OAuth2AuthenticationException(new OAuth2Error("email_conflict"));

        handler.onAuthenticationFailure(request, response, exception);

        assertEquals("https://example.com/login?error=email_conflict", response.getRedirectedUrl());
    }

    @Test
    void forwardsSocialAccountConflictErrorCodeInsteadOfGenericOne() throws Exception {
        // CustomOAuth2UserService.linkNewSocialAccount()가 uk_social_user_provider 위반을 감싸
        // 만드는 social_account_conflict도 화면까지 그대로 전달돼야 한다 - 화이트리스트에만 넣고
        // frontend(login/page.tsx)의 ERROR_MESSAGES에 빠뜨린 적이 있어(2026-08-07), 그 회귀를
        // 이 테스트가 잡는다.
        ReflectionTestUtils.setField(handler, "authorizedRedirectUri", "https://example.com/login");
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        AuthenticationException exception = new OAuth2AuthenticationException(new OAuth2Error("social_account_conflict"));

        handler.onAuthenticationFailure(request, response, exception);

        assertEquals("https://example.com/login?error=social_account_conflict", response.getRedirectedUrl());
    }
}
