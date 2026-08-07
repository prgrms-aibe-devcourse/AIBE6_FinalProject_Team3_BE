package com.algogyeyak.auth.handler;

import com.algogyeyak.auth.jwt.JwtAuthenticationFilter;
import com.algogyeyak.auth.jwt.JwtProvider;
import com.algogyeyak.auth.oauth.CookieAuthorizationRequestRepository;
import com.algogyeyak.auth.oauth.CookieUtils;
import com.algogyeyak.auth.oauth.CustomOAuth2User;
import com.algogyeyak.auth.token.RefreshTokenService;
import com.algogyeyak.global.error.ErrorCode;
import com.algogyeyak.global.exception.BusinessException;
import com.algogyeyak.user.entity.User;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OAuth2AuthenticationSuccessHandlerTest {

    private final JwtProvider jwtProvider =
            new JwtProvider("test-secret-key-must-be-at-least-32-bytes-long", 3600);
    private final RefreshTokenService refreshTokenService = mock(RefreshTokenService.class);
    private final CookieAuthorizationRequestRepository authorizationRequestRepository =
            mock(CookieAuthorizationRequestRepository.class);
    private final CookieUtils cookieUtils = mock(CookieUtils.class);
    private final OAuth2AuthenticationSuccessHandler handler = new OAuth2AuthenticationSuccessHandler(
            jwtProvider, refreshTokenService, authorizationRequestRepository, cookieUtils);

    private Authentication authenticationFor(Long userId) {
        return authenticationFor(userId, false);
    }

    private Authentication authenticationFor(Long userId, boolean linkedToExistingAccount) {
        User user = User.createOAuthUser("test@example.com", "테스트유저", "http://img");
        ReflectionTestUtils.setField(user, "id", userId);
        CustomOAuth2User principal = new CustomOAuth2User(user, Map.of("id", "123"), linkedToExistingAccount);
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

    @Test
    void appendsAccountLinkedNoticeWhenLoginLinkedToExistingAccount() throws Exception {
        ReflectionTestUtils.setField(handler, "authorizedRedirectUri", "https://example.com/login/success");
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationSuccess(request, response, authenticationFor(1L, true));

        // 새 계정 생성이 아니라 기존 계정에 방금 연동된 로그인이면, 로그인은 그대로 진행하되
        // 프론트가 안내 배너를 띄울 수 있도록 신호만 쿼리 파라미터로 실어 보낸다.
        assertEquals("https://example.com/login/success?notice=account_linked", response.getRedirectedUrl());
    }

    @Test
    void issuesRefreshTokenCookieScopedToRootPath() throws Exception {
        ReflectionTestUtils.setField(handler, "authorizedRedirectUri", "https://example.com/login/success");
        when(refreshTokenService.issue(any(User.class))).thenReturn("raw-refresh-token");
        when(refreshTokenService.getValiditySeconds()).thenReturn(1209600L);
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationSuccess(request, response, authenticationFor(1L));

        // path를 /auth로 좁히면 프론트 미들웨어가 보호 페이지 요청에서 이 쿠키를 아예 못 읽으므로,
        // Access Token과 동일하게 "/"로 발급한다 (4-인자 addCookie는 CookieUtils에서 path="/"로 기본 처리).
        verify(cookieUtils).addCookie(eq(response), eq(JwtAuthenticationFilter.REFRESH_TOKEN_COOKIE_NAME),
                eq("raw-refresh-token"), eq(1209600));
    }

    // refreshTokenService.issue()가 Redis 장애로 AUTH_TOKEN_STORE_UNAVAILABLE을 던지는 경우 -
    // 이 핸들러는 Spring Security 필터 체인 안에서 호출돼 GlobalExceptionHandler가 못 잡으므로,
    // 그대로 던지지 않고 로그인 실패와 동일하게 프론트로 에러 리다이렉트해야 한다.
    @Test
    void redirectsToFailureUrlWhenTokenIssuanceFailsDueToRedisOutage() throws Exception {
        ReflectionTestUtils.setField(handler, "authorizedRedirectUri", "https://example.com/login/success");
        when(refreshTokenService.issue(any(User.class)))
                .thenThrow(new BusinessException(ErrorCode.AUTH_TOKEN_STORE_UNAVAILABLE));
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationSuccess(request, response, authenticationFor(1L));

        verify(authorizationRequestRepository).removeAuthorizationRequest(request, response);
        assertTrue(response.getRedirectedUrl().startsWith("https://example.com/login/success"));
        assertTrue(response.getRedirectedUrl().contains("error=token_issue_failed"));
        // access token 쿠키는 이미 심어졌지만, refresh token 없이 반쪼가리 로그인 상태로 두면
        // 안 되므로 실패 처리하는 이 경로에서 지워야 한다.
        verify(cookieUtils).deleteCookie(response, JwtAuthenticationFilter.ACCESS_TOKEN_COOKIE_NAME);
        // 회귀 테스트 - 이번 로그인에서 새로 발급한 적은 없지만, 브라우저에 이전 세션의
        // refresh_token이 남아있을 수 있다. 실패로 확정하는 이 경로에서 함께 지워야, 나중에
        // Redis가 복구된 뒤 그 옛 refresh_token으로 예전 세션이 조용히 되살아나지 않는다.
        verify(cookieUtils).deleteCookie(response, JwtAuthenticationFilter.REFRESH_TOKEN_COOKIE_NAME);
    }
}
