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
}
