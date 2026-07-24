package com.algogyeyak.auth.controller;

import com.algogyeyak.auth.dto.LoginRequest;
import com.algogyeyak.auth.dto.PasswordUpdateRequest;
import com.algogyeyak.auth.dto.SignupRequest;
import com.algogyeyak.auth.jwt.JwtAuthenticationFilter;
import com.algogyeyak.auth.jwt.JwtProvider;
import com.algogyeyak.auth.jwt.JwtUserPrincipal;
import com.algogyeyak.auth.oauth.CookieUtils;
import com.algogyeyak.auth.service.LocalAuthService;
import com.algogyeyak.auth.token.RefreshTokenService;
import com.algogyeyak.global.error.ErrorCode;
import com.algogyeyak.global.exception.BusinessException;
import com.algogyeyak.global.response.ApiResponse;
import com.algogyeyak.user.entity.User;
import com.algogyeyak.user.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private final CookieUtils cookieUtils;
    private final UserRepository userRepository;
    private final JwtProvider jwtProvider;
    private final RefreshTokenService refreshTokenService;
    private final LocalAuthService localAuthService;

    public AuthController(
            CookieUtils cookieUtils,
            UserRepository userRepository,
            JwtProvider jwtProvider,
            RefreshTokenService refreshTokenService,
            LocalAuthService localAuthService) {
        this.cookieUtils = cookieUtils;
        this.userRepository = userRepository;
        this.jwtProvider = jwtProvider;
        this.refreshTokenService = refreshTokenService;
        this.localAuthService = localAuthService;
    }

    public record MeResponse(Long userId, String email, String nickname, String profileImageUrl, String role) {
    }

    // 소셜 로그인(OAuth2AuthenticationSuccessHandler)과 달리 리다이렉트가 아니라 REST 응답이므로,
    // 가입 직후 바로 온보딩(프로필 등록) 화면으로 넘어갈 수 있도록 여기서도 access/refresh 쿠키를
    // 즉시 발급해 자동 로그인 상태로 만든다.
    @PostMapping("/signup")
    public ResponseEntity<ApiResponse<MeResponse>> signup(
            @Valid @RequestBody SignupRequest request, HttpServletResponse response) {
        User user = localAuthService.signup(request.getEmail(), request.getPassword(), request.getNickname());
        issueAuthCookies(response, user);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(toMeResponse(user)));
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<MeResponse>> login(
            @Valid @RequestBody LoginRequest request, HttpServletResponse response) {
        User user = localAuthService.login(request.getEmail(), request.getPassword());
        issueAuthCookies(response, user);
        return ResponseEntity.ok(ApiResponse.success(toMeResponse(user)));
    }

    // 닉네임/프로필 사진은 JWT 발급 시점(OAuth 최초 로그인) 값이 아니라, 프로필 등록·수정 이후에도
    // 항상 최신 값이 반영되도록 매 요청마다 User 엔티티에서 조회한다.
    @GetMapping("/me")
    public ResponseEntity<ApiResponse<MeResponse>> me(@AuthenticationPrincipal JwtUserPrincipal principal) {
        // Access Token 자체는 유효해도 그 사이 탈퇴했거나 계정이 삭제된 사용자라면 세션을 더 이상
        // 유효하다고 취급하면 안 된다 — 실제 계정 없이 success 응답을 내려주는 것을 방지한다.
        User user = userRepository.findById(principal.userId())
                .filter(found -> !found.isWithdrawn())
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED, "존재하지 않거나 탈퇴한 사용자입니다."));

        MeResponse body = new MeResponse(
                principal.userId(), principal.email(), user.getNickname(), user.getProfileImageUrl(), principal.role().name());
        return ResponseEntity.ok(ApiResponse.success(body));
    }

    // 구글/카카오로만 가입한 계정도 여기서 비밀번호를 설정하면 그 즉시 같은 이메일로 로컬
    // 로그인이 가능해진다 — OAuth가 이미 이 이메일의 소유권을 검증해줬으므로 안전하다.
    @PatchMapping("/password")
    public ResponseEntity<ApiResponse<Void>> updatePassword(
            @AuthenticationPrincipal JwtUserPrincipal principal,
            @Valid @RequestBody PasswordUpdateRequest request) {
        localAuthService.setPassword(principal.userId(), request.getCurrentPassword(), request.getNewPassword());
        return ResponseEntity.ok(ApiResponse.successWithoutData());
    }

    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout(HttpServletRequest request, HttpServletResponse response) {
        CookieUtils.getCookie(request, JwtAuthenticationFilter.REFRESH_TOKEN_COOKIE_NAME)
                .ifPresent(cookie -> refreshTokenService.revoke(cookie.getValue()));

        cookieUtils.deleteCookie(response, JwtAuthenticationFilter.ACCESS_TOKEN_COOKIE_NAME);
        cookieUtils.deleteCookie(response, JwtAuthenticationFilter.REFRESH_TOKEN_COOKIE_NAME);
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
                (int) refreshTokenService.getValiditySeconds());

        return ResponseEntity.ok(ApiResponse.successWithoutData());
    }

    private void issueAuthCookies(HttpServletResponse response, User user) {
        String accessToken = jwtProvider.createAccessToken(user.getId(), user.getEmail(), user.getRole());
        cookieUtils.addCookie(response, JwtAuthenticationFilter.ACCESS_TOKEN_COOKIE_NAME, accessToken,
                (int) jwtProvider.getAccessTokenValiditySeconds());

        String refreshToken = refreshTokenService.issue(user);
        cookieUtils.addCookie(response, JwtAuthenticationFilter.REFRESH_TOKEN_COOKIE_NAME, refreshToken,
                (int) refreshTokenService.getValiditySeconds());
    }

    private MeResponse toMeResponse(User user) {
        return new MeResponse(
                user.getId(), user.getEmail(), user.getNickname(), user.getProfileImageUrl(), user.getRole().name());
    }
}
