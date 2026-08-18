package com.algogyeyak.auth.oauth;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.stereotype.Component;

/**
 * OAuth2 인가 요청을 HttpSession 대신 쿠키에 저장한다. Stateless(JWT 기반) 아키텍처에서
 * 소셜 로그인 리다이렉트 왕복 동안 세션에 의존하지 않기 위함. 쿠키 값은 HMAC으로 서명되어 있어
 * 클라이언트가 값을 조작해도 {@link CookieUtils#deserialize}에서 검증에 실패한다.
 */
@Slf4j
@Component
public class CookieAuthorizationRequestRepository implements AuthorizationRequestRepository<OAuth2AuthorizationRequest> {

    public static final String OAUTH2_AUTHORIZATION_REQUEST_COOKIE_NAME = "oauth2_auth_request";
    // 카카오톡 앱으로 전환해 로그인을 확인하고 돌아오거나, 구글이 보안키/2단계 인증을 요구하는
    // 경우 등 실사용자의 IdP 인증 왕복은 3분을 넘기기 쉽다 - 그 시간을 넘기면 이 쿠키가 조용히
    // 만료돼 로그인을 처음부터 다시 시작해야 한다. 쿠키가 HttpOnly+HMAC 서명돼 있어 유효시간을
    // 늘려도 노출 위험이 커지지 않으므로, 넉넉하게 10분으로 늘린다.
    private static final int COOKIE_EXPIRE_SECONDS = 600;

    private final CookieUtils cookieUtils;

    public CookieAuthorizationRequestRepository(CookieUtils cookieUtils) {
        this.cookieUtils = cookieUtils;
    }

    @Override
    public OAuth2AuthorizationRequest loadAuthorizationRequest(HttpServletRequest request) {
        return CookieUtils.getCookie(request, OAUTH2_AUTHORIZATION_REQUEST_COOKIE_NAME)
                .map(this::deserializeOrNull)
                .orElse(null);
    }

    @Override
    public void saveAuthorizationRequest(OAuth2AuthorizationRequest authorizationRequest,
                                          HttpServletRequest request, HttpServletResponse response) {
        if (authorizationRequest == null) {
            cookieUtils.deleteCookie(response, OAUTH2_AUTHORIZATION_REQUEST_COOKIE_NAME);
            return;
        }
        cookieUtils.addCookie(response, OAUTH2_AUTHORIZATION_REQUEST_COOKIE_NAME,
                cookieUtils.serialize(authorizationRequest), COOKIE_EXPIRE_SECONDS);
    }

    @Override
    public OAuth2AuthorizationRequest removeAuthorizationRequest(HttpServletRequest request, HttpServletResponse response) {
        OAuth2AuthorizationRequest authorizationRequest = loadAuthorizationRequest(request);
        cookieUtils.deleteCookie(response, OAUTH2_AUTHORIZATION_REQUEST_COOKIE_NAME);
        return authorizationRequest;
    }

    private OAuth2AuthorizationRequest deserializeOrNull(Cookie cookie) {
        try {
            return cookieUtils.deserialize(cookie, OAuth2AuthorizationRequest.class);
        } catch (CookieTamperedException e) {
            log.warn("Discarding invalid {} cookie: {}", OAUTH2_AUTHORIZATION_REQUEST_COOKIE_NAME, e.getMessage());
            return null;
        }
    }
}
