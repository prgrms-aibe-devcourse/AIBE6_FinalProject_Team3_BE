package com.algogyeyak.auth.service;

import com.algogyeyak.auth.jwt.AccessTokenRevocationService;
import com.algogyeyak.auth.jwt.JwtAuthenticationFilter;
import com.algogyeyak.auth.jwt.JwtProvider;
import com.algogyeyak.auth.oauth.CookieUtils;
import com.algogyeyak.auth.token.RefreshTokenService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.ZoneId;

// AuthController.logout()에서 쓰던 세션 무효화 로직을 user 도메인(회원 탈퇴)에서도 재사용할 수 있도록
// 뽑아낸 컴포넌트. 토큰이 없거나 이미 무효한 토큰(만료/서명 오류)이면 예외 없이 조용히 끝나지만,
// Redis 장애 시에는 기존 /auth/logout과 동일하게 fail-closed로 BusinessException(AUTH_TOKEN_STORE_UNAVAILABLE)을
// 그대로 던진다(RefreshTokenService.revoke / AccessTokenRevocationService.revoke 참고) — 완전한
// "예외 없음" 계약이 아니므로, 회원 탈퇴 후처리에서 이 예외를 어떻게 다룰지는 호출부에서 결정해야 한다.
@Service
public class SessionLogoutService {

    private static final Logger log = LoggerFactory.getLogger(SessionLogoutService.class);

    private final CookieUtils cookieUtils;
    private final JwtProvider jwtProvider;
    private final RefreshTokenService refreshTokenService;
    private final AccessTokenRevocationService accessTokenRevocationService;

    public SessionLogoutService(
            CookieUtils cookieUtils,
            JwtProvider jwtProvider,
            RefreshTokenService refreshTokenService,
            AccessTokenRevocationService accessTokenRevocationService) {
        this.cookieUtils = cookieUtils;
        this.jwtProvider = jwtProvider;
        this.refreshTokenService = refreshTokenService;
        this.accessTokenRevocationService = accessTokenRevocationService;
    }

    public void logout(HttpServletRequest request, HttpServletResponse response) {
        CookieUtils.getCookie(request, JwtAuthenticationFilter.REFRESH_TOKEN_COOKIE_NAME)
                .ifPresent(cookie -> refreshTokenService.revoke(cookie.getValue()));

        // 쿠키만 보면 안 된다 — /auth/me 등 다른 엔드포인트와 동일하게 JwtAuthenticationFilter.resolveToken로
        // 찾아야, Authorization: Bearer 헤더로 인증한 클라이언트(Swagger/Postman 등)도 로그아웃 시
        // access token이 실제로 무효화된다. 쿠키만 확인하던 이전 버전은 이 경로를 놓쳐 Bearer
        // 클라이언트의 access token이 로그아웃 후에도 자연 만료 전까지 계속 유효한 채로 남아 있었다.
        String accessToken = JwtAuthenticationFilter.resolveToken(request);
        if (StringUtils.hasText(accessToken)) {
            revokeAccessToken(accessToken);
        }

        cookieUtils.deleteCookie(response, JwtAuthenticationFilter.ACCESS_TOKEN_COOKIE_NAME);
        cookieUtils.deleteCookie(response, JwtAuthenticationFilter.REFRESH_TOKEN_COOKIE_NAME);
    }

    // 이미 만료되었거나 서명이 잘못된 access token은 애초에 인증에 쓰일 수 없으므로 블랙리스트에
    // 등록할 필요가 없다 — parseClaims가 던지는 JwtException은 그대로 무시하고 로그아웃을 계속 진행한다.
    private void revokeAccessToken(String accessToken) {
        try {
            Claims claims = jwtProvider.parseClaims(accessToken);
            // exp는 JWT 스펙상 사실상 항상 있고 JwtProvider도 항상 채워서 발급하므로 오늘 기준으로는
            // 도달 불가능에 가깝지만, 이게 null이면 바로 아래 toInstant()가 NPE를 던져 로그아웃
            // 자체가 실패한다 - jti 없는 경우와 마찬가지로 방어적으로 건너뛴다(2026-08-20 전수조사에서
            // 지적).
            if (claims.getExpiration() == null) {
                log.warn("access token에 만료 시각(exp)이 없어 블랙리스트에 등록할 수 없습니다");
                return;
            }
            LocalDateTime expiresAt = claims.getExpiration().toInstant()
                    .atZone(ZoneId.systemDefault())
                    .toLocalDateTime();
            accessTokenRevocationService.revoke(claims.getId(), expiresAt);
        } catch (JwtException | IllegalArgumentException e) {
            // 무시: 이미 무효한 토큰이라 블랙리스트에 올릴 대상이 없다. 로그아웃 자체는 계속
            // 진행되므로 warn/error는 과하고, 원인 진단이 필요할 때 확인할 수 있게 debug만 남긴다.
            log.debug("로그아웃 시 access token 무효화를 건너뜁니다(이미 무효한 토큰)", e);
        }
    }
}
