package com.algogyeyak.auth.oauth;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CookieAuthorizationRequestRepositoryTest {

    private static final String COOKIE_NAME =
            CookieAuthorizationRequestRepository.OAUTH2_AUTHORIZATION_REQUEST_COOKIE_NAME;

    private final CookieUtils cookieUtils = mock(CookieUtils.class);
    private final CookieAuthorizationRequestRepository repository = new CookieAuthorizationRequestRepository(cookieUtils);

    private static OAuth2AuthorizationRequest sampleAuthorizationRequest() {
        return OAuth2AuthorizationRequest.authorizationCode()
                .authorizationUri("https://kauth.kakao.com/oauth/authorize")
                .clientId("test-client-id")
                .redirectUri("https://example.com/login/oauth2/code/kakao")
                .authorizationRequestUri("https://kauth.kakao.com/oauth/authorize?client_id=test-client-id")
                .state("test-state")
                .build();
    }

    @Test
    void loadReturnsNullWhenNoCookiePresent() {
        MockHttpServletRequest request = new MockHttpServletRequest();

        assertNull(repository.loadAuthorizationRequest(request));
    }

    @Test
    void loadReturnsDeserializedRequestWhenCookiePresent() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(COOKIE_NAME, "signed-value"));
        OAuth2AuthorizationRequest expected = sampleAuthorizationRequest();
        when(cookieUtils.deserialize(any(Cookie.class), eq(OAuth2AuthorizationRequest.class))).thenReturn(expected);

        OAuth2AuthorizationRequest result = repository.loadAuthorizationRequest(request);

        assertEquals(expected, result);
    }

    @Test
    void loadReturnsNullWhenCookieTampered() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(COOKIE_NAME, "tampered-value"));
        when(cookieUtils.deserialize(any(Cookie.class), eq(OAuth2AuthorizationRequest.class)))
                .thenThrow(new CookieTamperedException("signature mismatch"));

        assertNull(repository.loadAuthorizationRequest(request));
    }

    @Test
    void saveDeletesCookieWhenAuthorizationRequestIsNull() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        repository.saveAuthorizationRequest(null, request, response);

        verify(cookieUtils).deleteCookie(response, COOKIE_NAME);
        verify(cookieUtils, never()).serialize(any());
    }

    @Test
    void saveAddsSerializedCookieWhenAuthorizationRequestPresent() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        OAuth2AuthorizationRequest authorizationRequest = sampleAuthorizationRequest();
        when(cookieUtils.serialize(authorizationRequest)).thenReturn("serialized-value");

        repository.saveAuthorizationRequest(authorizationRequest, request, response);

        verify(cookieUtils).addCookie(eq(response), eq(COOKIE_NAME), eq("serialized-value"), eq(600));
    }

    @Test
    void removeReturnsLoadedRequestAndDeletesCookie() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(COOKIE_NAME, "signed-value"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        OAuth2AuthorizationRequest expected = sampleAuthorizationRequest();
        when(cookieUtils.deserialize(any(Cookie.class), eq(OAuth2AuthorizationRequest.class))).thenReturn(expected);

        OAuth2AuthorizationRequest result = repository.removeAuthorizationRequest(request, response);

        assertEquals(expected, result);
        verify(cookieUtils).deleteCookie(response, COOKIE_NAME);
    }
}
