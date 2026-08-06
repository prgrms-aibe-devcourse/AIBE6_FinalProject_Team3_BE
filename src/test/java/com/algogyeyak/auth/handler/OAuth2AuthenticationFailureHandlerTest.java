package com.algogyeyak.auth.handler;

import com.algogyeyak.auth.oauth.CookieAuthorizationRequestRepository;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;
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
    void redirectsWithTheSpecificOAuth2ErrorCode() throws Exception {
        ReflectionTestUtils.setField(handler, "authorizedRedirectUri", "https://example.com/login");
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        // CustomOAuth2UserService가 만드는 구체적인 코드(account_blocked/email_conflict 등)를 그대로
        // 전달해야, 프론트가 "정지된 계정"과 "알 수 없는 오류"를 구분해 보여줄 수 있다 - 예전엔 이
        // 코드와 무관하게 항상 oauth_login_failed로 덮어썼다.
        AuthenticationException exception = new OAuth2AuthenticationException(new OAuth2Error("account_blocked"));

        handler.onAuthenticationFailure(request, response, exception);

        verify(authorizationRequestRepository).removeAuthorizationRequest(request, response);
        assertEquals("https://example.com/login?error=account_blocked", response.getRedirectedUrl());
    }

    @Test
    void fallsBackToGenericErrorCodeWhenExceptionIsNotAnOAuth2AuthenticationException() throws Exception {
        ReflectionTestUtils.setField(handler, "authorizedRedirectUri", "https://example.com/login");
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        AuthenticationException exception = new BadCredentialsException("some other auth failure");

        handler.onAuthenticationFailure(request, response, exception);

        verify(authorizationRequestRepository).removeAuthorizationRequest(request, response);
        assertEquals("https://example.com/login?error=oauth_login_failed", response.getRedirectedUrl());
    }
}
