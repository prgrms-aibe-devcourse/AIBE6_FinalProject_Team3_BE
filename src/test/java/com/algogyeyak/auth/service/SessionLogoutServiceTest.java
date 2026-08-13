package com.algogyeyak.auth.service;

import com.algogyeyak.auth.jwt.AccessTokenRevocationService;
import com.algogyeyak.auth.jwt.JwtAuthenticationFilter;
import com.algogyeyak.auth.jwt.JwtProvider;
import com.algogyeyak.auth.oauth.CookieUtils;
import com.algogyeyak.auth.token.RefreshTokenService;
import com.algogyeyak.global.error.ErrorCode;
import com.algogyeyak.global.exception.BusinessException;
import com.algogyeyak.user.enums.Role;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

// user 도메인(회원 탈퇴)이 이 서비스에 기대는 계약(토큰이 없거나 이미 무효해도 예외 없이 끝나지만,
// Redis 장애 시에는 그대로 예외가 전파된다)을 AuthControllerTest의 간접 검증과 별개로 이 클래스
// 자체에서 고정해둔다.
class SessionLogoutServiceTest {

    private final CookieUtils cookieUtils =
            new CookieUtils(false, "Lax", "", "test-state-signing-key-must-be-at-least-32-bytes");
    private final JwtProvider jwtProvider =
            new JwtProvider("test-secret-key-must-be-at-least-32-bytes-long", 3600);
    private final RefreshTokenService refreshTokenService = mock(RefreshTokenService.class);
    private final AccessTokenRevocationService accessTokenRevocationService = mock(AccessTokenRevocationService.class);
    private final SessionLogoutService sessionLogoutService =
            new SessionLogoutService(cookieUtils, jwtProvider, refreshTokenService, accessTokenRevocationService);

    private static String jtiOf(JwtProvider jwtProvider, String token) {
        Claims claims = jwtProvider.parseClaims(token);
        return claims.getId();
    }

    @Test
    void logoutIsNoOpWhenNoCookiesOrTokenPresent() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        sessionLogoutService.logout(request, response);

        verifyNoInteractions(refreshTokenService, accessTokenRevocationService);
    }

    @Test
    void logoutAlwaysDeletesBothCookies() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        sessionLogoutService.logout(request, response);

        var setCookieHeaders = response.getHeaders("Set-Cookie");
        assertEquals(2, setCookieHeaders.size());
        assertTrue(setCookieHeaders.stream().anyMatch(h ->
                h.contains(JwtAuthenticationFilter.ACCESS_TOKEN_COOKIE_NAME + "=") && h.contains("Max-Age=0")));
        assertTrue(setCookieHeaders.stream().anyMatch(h ->
                h.contains(JwtAuthenticationFilter.REFRESH_TOKEN_COOKIE_NAME + "=") && h.contains("Max-Age=0")));
    }

    @Test
    void logoutRevokesRefreshTokenWhenCookiePresent() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(JwtAuthenticationFilter.REFRESH_TOKEN_COOKIE_NAME, "raw-refresh-token"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        sessionLogoutService.logout(request, response);

        verify(refreshTokenService).revoke("raw-refresh-token");
    }

    @Test
    void logoutRevokesAccessTokenJtiWhenValidTokenInCookie() {
        String token = jwtProvider.createAccessToken(1L, "test@example.com", Role.USER);
        String expectedJti = jtiOf(jwtProvider, token);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(JwtAuthenticationFilter.ACCESS_TOKEN_COOKIE_NAME, token));
        MockHttpServletResponse response = new MockHttpServletResponse();

        sessionLogoutService.logout(request, response);

        verify(accessTokenRevocationService).revoke(eq(expectedJti), any(LocalDateTime.class));
    }

    @Test
    void logoutRevokesAccessTokenWhenPresentedViaBearerHeader() {
        // Swagger/Postman처럼 쿠키 없이 Authorization: Bearer 헤더만으로 인증하는 클라이언트도
        // 커버해야 한다 — AuthControllerTest의 동일 이름 회귀 테스트와 같은 이유.
        String token = jwtProvider.createAccessToken(2L, "bearer@example.com", Role.USER);
        String expectedJti = jtiOf(jwtProvider, token);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();

        sessionLogoutService.logout(request, response);

        verify(accessTokenRevocationService).revoke(eq(expectedJti), any(LocalDateTime.class));
    }

    @Test
    void logoutSwallowsInvalidAccessTokenSilently() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(JwtAuthenticationFilter.ACCESS_TOKEN_COOKIE_NAME, "not-a-valid-jwt"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        sessionLogoutService.logout(request, response);

        verifyNoInteractions(accessTokenRevocationService);
    }

    @Test
    void logoutPropagatesBusinessExceptionWhenRefreshTokenRevokeFails() {
        // 계약의 핵심: Redis 장애 시에는 "예외 없음"이 아니라 기존 /auth/logout과 동일하게
        // fail-closed로 예외가 그대로 전파돼야 한다.
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(JwtAuthenticationFilter.REFRESH_TOKEN_COOKIE_NAME, "raw-refresh-token"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        doThrow(new BusinessException(ErrorCode.AUTH_TOKEN_STORE_UNAVAILABLE))
                .when(refreshTokenService).revoke(anyString());

        BusinessException exception = assertThrows(BusinessException.class,
                () -> sessionLogoutService.logout(request, response));

        assertEquals(ErrorCode.AUTH_TOKEN_STORE_UNAVAILABLE, exception.getErrorCode());
        verifyNoInteractions(accessTokenRevocationService);
        assertEquals(0, response.getHeaders("Set-Cookie").size());
    }

    @Test
    void logoutPropagatesBusinessExceptionWhenAccessTokenRevokeFails() {
        String token = jwtProvider.createAccessToken(3L, "fails@example.com", Role.USER);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(JwtAuthenticationFilter.ACCESS_TOKEN_COOKIE_NAME, token));
        MockHttpServletResponse response = new MockHttpServletResponse();
        doThrow(new BusinessException(ErrorCode.AUTH_TOKEN_STORE_UNAVAILABLE))
                .when(accessTokenRevocationService).revoke(anyString(), any(LocalDateTime.class));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> sessionLogoutService.logout(request, response));

        assertEquals(ErrorCode.AUTH_TOKEN_STORE_UNAVAILABLE, exception.getErrorCode());
        assertEquals(0, response.getHeaders("Set-Cookie").size());
    }
}
