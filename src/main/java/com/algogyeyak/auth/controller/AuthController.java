package com.algogyeyak.auth.controller;

import com.algogyeyak.auth.dto.LoginRequest;
import com.algogyeyak.auth.dto.PasswordPolicy;
import com.algogyeyak.auth.dto.PasswordUpdateRequest;
import com.algogyeyak.auth.dto.SignupRequest;
import com.algogyeyak.auth.util.EmailNormalizer;
import com.algogyeyak.auth.jwt.AccessTokenRevocationService;
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
import com.algogyeyak.user.enums.Role;
import com.algogyeyak.user.repository.UserRepository;
import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.ZoneId;

@Tag(name = "Auth", description = "회원가입, 로그인(로컬/소셜), 토큰 재발급, 비밀번호 관리 API")
@RestController
@RequestMapping("/auth")
public class AuthController {

    private final CookieUtils cookieUtils;
    private final UserRepository userRepository;
    private final JwtProvider jwtProvider;
    private final RefreshTokenService refreshTokenService;
    private final LocalAuthService localAuthService;
    private final AccessTokenRevocationService accessTokenRevocationService;

    @Value("${app.dev-login.enabled}")
    private boolean devLoginEnabled;

    @Value("${app.dev-login.email}")
    private String devLoginEmail;

    public AuthController(
            CookieUtils cookieUtils,
            UserRepository userRepository,
            JwtProvider jwtProvider,
            RefreshTokenService refreshTokenService,
            LocalAuthService localAuthService,
            AccessTokenRevocationService accessTokenRevocationService) {
        this.cookieUtils = cookieUtils;
        this.userRepository = userRepository;
        this.jwtProvider = jwtProvider;
        this.refreshTokenService = refreshTokenService;
        this.localAuthService = localAuthService;
        this.accessTokenRevocationService = accessTokenRevocationService;
    }

    public record MeResponse(
            @Schema(description = "사용자 ID", example = "1") Long userId,
            @Schema(description = "이메일", example = "user@example.com") String email,
            @Schema(description = "닉네임", example = "algo") String nickname,
            @Schema(description = "프로필 이미지 URL") String profileImageUrl,
            @Schema(description = "권한", example = "USER") String role) {
    }

    public record PasswordPolicyResponse(
            @Schema(description = "HTML <input pattern=\"...\"> 속성에 그대로 쓸 수 있는 정규식(앞뒤 ^/$ 없음)",
                    example = "(?=.*[A-Za-z])(?=.*\\d)[\\x21-\\x7E]{8,72}") String pattern,
            @Schema(description = "형식 위반 시 보여줄 안내 문구") String message) {
    }

    // 비밀번호 정책(정규식/안내 문구)이 frontend에 하드코딩돼 있으면 두 곳이 어긋날 때 "프론트는
    // 통과, 서버는 거부"하는 이중 실패가 생긴다. PasswordPolicy(SignupRequest/PasswordUpdateRequest가
    // 실제 검증에 쓰는 바로 그 상수)를 여기서 그대로 내려줘서, frontend는 값을 복사해두지 않고 이
    // 엔드포인트를 유일한 소스로 삼는다. 로그인 전(회원가입 폼)에도 필요해 인증 없이 열어둔다.
    @Operation(summary = "비밀번호 정책 조회", description = "회원가입/비밀번호 변경 폼에서 클라이언트 측 선제 검증에 쓸 비밀번호 정책(정규식, 안내 문구)을 반환한다. 최종 판단은 signup/password 엔드포인트가 서버에서 다시 수행한다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공")
    @GetMapping("/password-policy")
    public ResponseEntity<ApiResponse<PasswordPolicyResponse>> passwordPolicy() {
        return ResponseEntity.ok(ApiResponse.success(
                new PasswordPolicyResponse(PasswordPolicy.HTML_INPUT_PATTERN, PasswordPolicy.MESSAGE)));
    }

    // 소셜 로그인(OAuth2AuthenticationSuccessHandler)과 달리 리다이렉트가 아니라 REST 응답이므로,
    // 가입 직후 바로 온보딩(프로필 등록) 화면으로 넘어갈 수 있도록 여기서도 access/refresh 쿠키를
    // 즉시 발급해 자동 로그인 상태로 만든다.
    @Operation(summary = "회원가입", description = "이메일/비밀번호로 가입한다. 성공 시 access/refresh 토큰을 쿠키로 즉시 발급해 자동 로그인 상태로 만든다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "회원가입 성공")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "요청 형식이 올바르지 않음 (이메일/비밀번호/닉네임 형식 위반)")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "이미 가입된 이메일 또는 사용 중인 닉네임 (AUTH_EMAIL_ALREADY_EXISTS / AUTH_NICKNAME_ALREADY_EXISTS)")
    @PostMapping("/signup")
    public ResponseEntity<ApiResponse<MeResponse>> signup(
            @Valid @RequestBody SignupRequest request, HttpServletResponse response) {
        User user = localAuthService.signup(request.getEmail(), request.getPassword(), request.getNickname());
        issueAuthCookies(response, user);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(toMeResponse(user)));
    }

    @Operation(summary = "로그인", description = "이메일/비밀번호로 로그인한다. 성공 시 access/refresh 토큰을 쿠키로 발급한다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "로그인 성공")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "요청 형식이 올바르지 않음 (이메일/비밀번호 누락)")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "이메일 또는 비밀번호가 올바르지 않음 (AUTH_INVALID_CREDENTIALS)")
    @PostMapping("/login")
    public ResponseEntity<ApiResponse<MeResponse>> login(
            @Valid @RequestBody LoginRequest request, HttpServletResponse response) {
        User user = localAuthService.login(request.getEmail(), request.getPassword());
        issueAuthCookies(response, user);
        return ResponseEntity.ok(ApiResponse.success(toMeResponse(user)));
    }

    // 닉네임/프로필 사진/권한은 JWT 발급 시점(로그인 시점) 값이 아니라, 그 이후 변경돼도 항상 최신
    // 값이 반영되도록 매 요청마다 User 엔티티에서 조회한다. role도 예외가 아니다 — 그렇지 않으면
    // 관리자 권한이 회수된 사용자가 access token 만료(최대 30분) 전까지는 이 응답에서 계속 예전
    // role을 보게 된다.
    @Operation(summary = "내 정보 조회", description = "access token 쿠키로 인증된 사용자 본인의 정보를 반환한다. Authorization: Bearer 헤더로도 인증 가능하다.")
    @SecurityRequirement(name = "access_token")
    @SecurityRequirement(name = "bearerAuth")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증되지 않았거나 탈퇴한 사용자")
    @GetMapping("/me")
    public ResponseEntity<ApiResponse<MeResponse>> me(@AuthenticationPrincipal JwtUserPrincipal principal) {
        // Access Token 자체는 유효해도 그 사이 탈퇴했거나 계정이 삭제된 사용자라면 세션을 더 이상
        // 유효하다고 취급하면 안 된다 — 실제 계정 없이 success 응답을 내려주는 것을 방지한다.
        User user = userRepository.findById(principal.userId())
                .filter(found -> !found.isWithdrawn())
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED, "존재하지 않거나 탈퇴한 사용자입니다."));

        return ResponseEntity.ok(ApiResponse.success(toMeResponse(user)));
    }

    // 구글/카카오로만 가입한 계정도 여기서 비밀번호를 설정하면 그 즉시 같은 이메일로 로컬
    // 로그인이 가능해진다 — OAuth가 이미 이 이메일의 소유권을 검증해줬으므로 안전하다.
    @Operation(summary = "비밀번호 설정/변경", description = "로그인된 사용자 본인의 비밀번호를 설정하거나 변경한다. OAuth 전용 계정도 이 API로 로컬 로그인용 비밀번호를 추가로 확보할 수 있다. Authorization: Bearer 헤더로도 인증 가능하다.")
    @SecurityRequirement(name = "access_token")
    @SecurityRequirement(name = "bearerAuth")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "변경 성공")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "새 비밀번호 형식이 올바르지 않음 (영문+숫자 포함, 8~72자)")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "access token이 없거나 유효하지 않음 (AUTH_TOKEN_MISSING / AUTH_TOKEN_INVALID / AUTH_TOKEN_EXPIRED)")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "이메일이 연동되지 않은 계정, 현재 비밀번호 불일치, 또는 dev-login 계정 (AUTH_EMAIL_REQUIRED_FOR_PASSWORD / AUTH_CURRENT_PASSWORD_MISMATCH / FORBIDDEN)")
    @PatchMapping("/password")
    public ResponseEntity<ApiResponse<Void>> updatePassword(
            @AuthenticationPrincipal JwtUserPrincipal principal,
            @Valid @RequestBody PasswordUpdateRequest request) {
        localAuthService.setPassword(principal.userId(), request.getCurrentPassword(), request.getNewPassword());
        return ResponseEntity.ok(ApiResponse.successWithoutData());
    }

    // 개발 편의용 "관리자로 로그인" 버튼. AdminAccountSeeder가 만들어둔 admin 계정으로 자격 증명
    // 없이 바로 로그인시킨다 — devLoginEnabled가 false(운영 등)면 엔드포인트가 존재하는지조차
    // 드러내지 않도록 404로 응답한다.
    @Hidden
    @PostMapping("/dev-login")
    public ResponseEntity<ApiResponse<MeResponse>> devLogin(HttpServletResponse response) {
        if (!devLoginEnabled) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }

        User user = userRepository.findByEmail(EmailNormalizer.normalize(devLoginEmail))
                .filter(found -> found.getRole() == Role.ADMIN)
                .filter(found -> !found.isWithdrawn())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        issueAuthCookies(response, user);
        return ResponseEntity.ok(ApiResponse.success(toMeResponse(user)));
    }

    @Operation(summary = "로그아웃", description = "refresh_token 쿠키가 있으면 서버에서 즉시 무효화(revoke)하고, access token(access_token 쿠키 또는 Authorization: Bearer 헤더)이 있으면 그 jti를 만료 시각까지 블랙리스트에 등록해 남은 유효기간 동안에도 즉시 무효화한다. 이후 access/refresh 쿠키를 삭제한다. 아무것도 없어도(이미 만료/삭제된 경우 등) 항상 200으로 성공 처리되므로 필수 인증 요구사항으로 문서화하지 않는다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "로그아웃 성공")
    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout(HttpServletRequest request, HttpServletResponse response) {
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
        return ResponseEntity.ok(ApiResponse.successWithoutData());
    }

    // 이미 만료되었거나 서명이 잘못된 access token은 애초에 인증에 쓰일 수 없으므로 블랙리스트에
    // 등록할 필요가 없다 — parseClaims가 던지는 JwtException은 그대로 무시하고 로그아웃을 계속 진행한다.
    private void revokeAccessToken(String accessToken) {
        try {
            Claims claims = jwtProvider.parseClaims(accessToken);
            LocalDateTime expiresAt = claims.getExpiration().toInstant()
                    .atZone(ZoneId.systemDefault())
                    .toLocalDateTime();
            accessTokenRevocationService.revoke(claims.getId(), expiresAt);
        } catch (JwtException | IllegalArgumentException e) {
            // 무시: 이미 무효한 토큰이라 블랙리스트에 올릴 대상이 없다.
        }
    }

    // Access Token이 만료된 상태에서 호출되는 것이 전제이므로 인증 없이 접근 가능해야 한다 (SecurityConfig permitAll).
    @Operation(summary = "토큰 재발급", description = "refresh token 쿠키를 검증해 access/refresh 토큰을 재발급(로테이션)한 뒤 쿠키로 내려준다. 토큰은 응답 body가 아닌 Set-Cookie로 전달된다.")
    @SecurityRequirement(name = "refresh_token")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "재발급 성공")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "refresh token이 없거나 유효하지 않거나 만료됨 (AUTH_REFRESH_TOKEN_MISSING / AUTH_REFRESH_TOKEN_INVALID / AUTH_REFRESH_TOKEN_EXPIRED)")
    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<Void>> refresh(HttpServletRequest request, HttpServletResponse response) {
        String rawRefreshToken = CookieUtils.getCookie(request, JwtAuthenticationFilter.REFRESH_TOKEN_COOKIE_NAME)
                .map(cookie -> cookie.getValue())
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_REFRESH_TOKEN_MISSING));

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
