package com.algogyeyak.auth.handler;

import com.algogyeyak.auth.jwt.JwtAuthenticationFilter;
import com.algogyeyak.auth.jwt.JwtProvider;
import com.algogyeyak.auth.oauth.CookieAuthorizationRequestRepository;
import com.algogyeyak.auth.oauth.CookieUtils;
import com.algogyeyak.auth.oauth.CustomOAuth2User;
import com.algogyeyak.auth.token.RefreshTokenService;
import com.algogyeyak.user.entity.User;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
@RequiredArgsConstructor
public class OAuth2AuthenticationSuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

    private final JwtProvider jwtProvider;
    private final RefreshTokenService refreshTokenService;
    private final CookieAuthorizationRequestRepository authorizationRequestRepository;
    private final CookieUtils cookieUtils;

    @Value("${app.oauth2.authorized-redirect-uri}")
    private String authorizedRedirectUri;

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response, Authentication authentication)
            throws IOException {
        User user = ((CustomOAuth2User) authentication.getPrincipal()).getUser();

        String accessToken = jwtProvider.createAccessToken(user.getId(), user.getEmail(), user.getRole());
        cookieUtils.addCookie(response, JwtAuthenticationFilter.ACCESS_TOKEN_COOKIE_NAME, accessToken,
                (int) jwtProvider.getAccessTokenValiditySeconds());

        // 프론트 미들웨어가 보호 페이지 요청(/home 등)에서 이 쿠키를 읽어 만료된 Access Token을
        // 재발급해야 하는데, path를 /auth로 좁히면 브라우저가 그런 요청에 아예 쿠키를 실어보내지
        // 않는다 — 그래서 Access Token과 동일하게 path를 "/"로 둔다.
        String refreshToken = refreshTokenService.issue(user);
        cookieUtils.addCookie(response, JwtAuthenticationFilter.REFRESH_TOKEN_COOKIE_NAME, refreshToken,
                (int) refreshTokenService.getValiditySeconds());

        authorizationRequestRepository.removeAuthorizationRequest(request, response);
        clearAuthenticationAttributes(request);

        getRedirectStrategy().sendRedirect(request, response, authorizedRedirectUri);
    }
}
