package com.algogyeyak.auth.controller;

import com.algogyeyak.auth.jwt.JwtAuthenticationFilter;
import com.algogyeyak.auth.jwt.JwtProvider;
import com.algogyeyak.auth.jwt.JwtUserPrincipal;
import com.algogyeyak.auth.oauth.CookieUtils;
import com.algogyeyak.auth.token.RefreshTokenService;
import com.algogyeyak.global.error.ErrorCode;
import com.algogyeyak.global.exception.BusinessException;
import com.algogyeyak.global.response.ApiResponse;
import com.algogyeyak.user.entity.User;
import com.algogyeyak.user.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private final CookieUtils cookieUtils;
    private final UserRepository userRepository;
    private final JwtProvider jwtProvider;
    private final RefreshTokenService refreshTokenService;

    public AuthController(
            CookieUtils cookieUtils,
            UserRepository userRepository,
            JwtProvider jwtProvider,
            RefreshTokenService refreshTokenService) {
        this.cookieUtils = cookieUtils;
        this.userRepository = userRepository;
        this.jwtProvider = jwtProvider;
        this.refreshTokenService = refreshTokenService;
    }

    public record MeResponse(Long userId, String email, String nickname, String profileImageUrl, String role) {
    }

    // 닉네임/프로필 사진은 JWT 발급 시점(OAuth 최초 로그인) 값이 아니라, 프로필 등록·수정 이후에도
    // 항상 최신 값이 반영되도록 매 요청마다 User 엔티티에서 조회한다.
    @GetMapping("/me")
    public ResponseEntity<ApiResponse<MeResponse>> me(@AuthenticationPrincipal JwtUserPrincipal principal) {
        User user = userRepository.findById(principal.userId()).orElse(null);
        String nickname = user != null ? user.getNickname() : null;
        String profileImageUrl = user != null ? user.getProfileImageUrl() : null;
        MeResponse body = new MeResponse(
                principal.userId(), principal.email(), nickname, profileImageUrl, principal.role().name());
        return ResponseEntity.ok(ApiResponse.success(body));
    }

    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout(HttpServletRequest request, HttpServletResponse response) {
        CookieUtils.getCookie(request, JwtAuthenticationFilter.REFRESH_TOKEN_COOKIE_NAME)
                .ifPresent(cookie -> refreshTokenService.revoke(cookie.getValue()));

        cookieUtils.deleteCookie(response, JwtAuthenticationFilter.ACCESS_TOKEN_COOKIE_NAME);
        cookieUtils.deleteCookie(response, JwtAuthenticationFilter.REFRESH_TOKEN_COOKIE_NAME, "/auth");
        return ResponseEntity.ok(ApiResponse.successWithoutData());
    }

    // Access Token이 만료된 상태에서 호출되는 것이 전제이므로 인증 없이 접근 가능해야 한다 (SecurityConfig permitAll).
    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<Void>> refresh(HttpServletRequest request, HttpServletResponse response) {
        String rawRefreshToken = CookieUtils.getCookie(request, JwtAuthenticationFilter.REFRESH_TOKEN_COOKIE_NAME)
                .map(cookie -> cookie.getValue())
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED, "Refresh Token이 없습니다."));

        RefreshTokenService.RotationResult result = refreshTokenService.rotate(rawRefreshToken);
        User user = result.user();

        String accessToken = jwtProvider.createAccessToken(user.getId(), user.getEmail(), user.getRole());
        cookieUtils.addCookie(response, JwtAuthenticationFilter.ACCESS_TOKEN_COOKIE_NAME, accessToken,
                (int) jwtProvider.getAccessTokenValiditySeconds());
        cookieUtils.addCookie(response, JwtAuthenticationFilter.REFRESH_TOKEN_COOKIE_NAME, result.rawToken(),
                (int) refreshTokenService.getValiditySeconds(), "/auth");

        return ResponseEntity.ok(ApiResponse.successWithoutData());
    }
}
