package com.algogyeyak.auth.controller;

import com.algogyeyak.auth.dto.LoginRequest;
import com.algogyeyak.auth.dto.PasswordPolicy;
import com.algogyeyak.auth.dto.PasswordUpdateRequest;
import com.algogyeyak.auth.dto.SignupRequest;
import com.algogyeyak.auth.util.EmailNormalizer;
import com.algogyeyak.auth.jwt.JwtAuthenticationFilter;
import com.algogyeyak.auth.jwt.JwtProvider;
import com.algogyeyak.auth.jwt.JwtUserPrincipal;
import com.algogyeyak.auth.oauth.CookieUtils;
import com.algogyeyak.auth.service.LocalAuthService;
import com.algogyeyak.auth.service.SessionLogoutService;
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
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.Cookie;
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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;

@Tag(name = "Auth", description = "회원가입, 로그인(로컬/소셜), 토큰 재발급, 비밀번호 관리 API")
@RestController
@RequestMapping("/auth")
public class AuthController {

    private final CookieUtils cookieUtils;
    private final UserRepository userRepository;
    private final JwtProvider jwtProvider;
    private final RefreshTokenService refreshTokenService;
    private final LocalAuthService localAuthService;
    private final SessionLogoutService sessionLogoutService;

    @Value("${app.dev-login.enabled}")
    private boolean devLoginEnabled;

    @Value("${app.dev-login.email}")
    private String devLoginEmail;

    // 운영에서도 devLoginEnabled를 켤 수 있게 되면서(스위치 하나만으로는 "아무나" 이 엔드포인트를
    // 호출해 admin이 될 수 있다) 추가한 공유 비밀 값 — 이 값을 아는 사람만 dev-login을 쓸 수 있다.
    @Value("${app.dev-login.secret:}")
    private String devLoginSecret;

    // enabled=true인데 secret이 비어있으면, 스위치만 켜졌을 뿐 아무런 잠금도 없는 상태로 조용히
    // 뜬다 - JWT_SECRET 등과 같은 이유로 이 조합은 기동 자체를 막는다(운영 배포 시 DEV_LOGIN_ENABLED만
    // 켜고 DEV_LOGIN_SECRET을 깜빡하는 실수를 막기 위함).
    @PostConstruct
    private void validateDevLoginConfig() {
        if (devLoginEnabled && !StringUtils.hasText(devLoginSecret)) {
            throw new IllegalStateException(
                    "app.dev-login.enabled=true requires app.dev-login.secret to be set");
        }
    }

    public AuthController(
            CookieUtils cookieUtils,
            UserRepository userRepository,
            JwtProvider jwtProvider,
            RefreshTokenService refreshTokenService,
            LocalAuthService localAuthService,
            SessionLogoutService sessionLogoutService) {
        this.cookieUtils = cookieUtils;
        this.userRepository = userRepository;
        this.jwtProvider = jwtProvider;
        this.refreshTokenService = refreshTokenService;
        this.localAuthService = localAuthService;
        this.sessionLogoutService = sessionLogoutService;
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
        try {
            issueAuthCookies(response, user);
        } catch (BusinessException e) {
            // signup()은 방금 User를 REQUIRES_NEW로 이미 커밋했다 - refresh token 발급(Redis 장애
            // 등)이 실패해도 access 쿠키는 issueAuthCookies()가 지워주지만, 계정 자체는 세션 없이
            // 그대로 남는다. 이 상태에서 재시도하면 AUTH_EMAIL_ALREADY_EXISTS로 막혀 사용자가 같은
            // 이메일로 다시 가입도 로그인도 시도하기 어려워진다 - 방금 이 요청에서 만든 계정임이
            // 확실하므로, 실패로 확정하는 이 경로에서 함께 되돌려 재시도 시 처음부터 깨끗하게
            // 다시 가입할 수 있게 한다.
            localAuthService.deleteNewlyCreatedUserAfterSessionSetupFailure(user.getId());
            throw e;
        }
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
                .filter(found -> !found.isWithdrawn() && !found.isSuspended())
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
    // 없이 바로 로그인시킨다 — devLoginEnabled가 false(운영 등)면, 그리고 key가 devLoginSecret과
    // 일치하지 않으면 엔드포인트가 존재하는지조차 드러내지 않도록 둘 다 동일하게 404로 응답한다.
    // key는 쿼리 파라미터가 아니라 헤더로 받는다 - 쿼리스트링은 서버 액세스 로그, 프록시/LB 로그,
    // APM, 브라우저 히스토리 등에 평문으로 남기 쉬워 공유 비밀값을 싣기에 적절하지 않다.
    @Hidden
    @PostMapping("/dev-login")
    public ResponseEntity<ApiResponse<MeResponse>> devLogin(
            @RequestHeader(value = "X-Dev-Login-Key", required = false) String key, HttpServletResponse response) {
        if (!devLoginEnabled || !constantTimeEquals(devLoginSecret, key)) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }

        User user = userRepository.findByEmail(EmailNormalizer.normalize(devLoginEmail))
                .filter(found -> found.getRole() == Role.ADMIN)
                .filter(found -> !found.isWithdrawn() && !found.isSuspended())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        issueAuthCookies(response, user);
        return ResponseEntity.ok(ApiResponse.success(toMeResponse(user)));
    }

    // String.equals는 첫 불일치 문자에서 바로 리턴해 비교 시간이 일치한 접두 길이에 약하게
    // 비례한다 - 공유 비밀값(devLoginSecret) 검증에는 CookieUtils의 HMAC 검증과 동일하게
    // MessageDigest.isEqual(상수 시간 비교)을 쓴다. key가 아예 없으면(헤더 미전송) 바이트 비교
    // 자체를 하지 않고 바로 false로 처리한다.
    private boolean constantTimeEquals(String expected, String actual) {
        if (actual == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }

    @Operation(summary = "로그아웃", description = "refresh_token 쿠키가 있으면 서버에서 즉시 무효화(revoke)하고, access token(access_token 쿠키 또는 Authorization: Bearer 헤더)이 있으면 그 jti를 만료 시각까지 블랙리스트에 등록해 남은 유효기간 동안에도 즉시 무효화한다. 이후 access/refresh 쿠키를 삭제한다. 아무것도 없어도(이미 만료/삭제된 경우 등) 항상 200으로 성공 처리되므로 필수 인증 요구사항으로 문서화하지 않는다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "로그아웃 성공")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503", description = "Redis 장애로 blacklist/refresh token 저장소에 연결할 수 없어 무효화를 보장할 수 없음 (AUTH_TOKEN_STORE_UNAVAILABLE) — 쿠키 삭제도 진행되지 않으므로 재시도가 필요하다")
    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout(HttpServletRequest request, HttpServletResponse response) {
        sessionLogoutService.logout(request, response);
        return ResponseEntity.ok(ApiResponse.successWithoutData());
    }

    // Access Token이 만료된 상태에서 호출되는 것이 전제이므로 인증 없이 접근 가능해야 한다 (SecurityConfig permitAll).
    @Operation(summary = "토큰 재발급", description = "refresh token 쿠키를 검증해 access/refresh 토큰을 재발급(로테이션)한 뒤 쿠키로 내려준다. 토큰은 응답 body가 아닌 Set-Cookie로 전달된다.")
    @SecurityRequirement(name = "refresh_token")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "재발급 성공")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "refresh token이 없거나 유효하지 않음 (AUTH_REFRESH_TOKEN_MISSING / AUTH_REFRESH_TOKEN_INVALID — Redis TTL로 자연 만료된 토큰도 후자로 응답됨)")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503", description = "Redis 장애로 refresh token 저장소에 연결할 수 없음 (AUTH_TOKEN_STORE_UNAVAILABLE)")
    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<Void>> refresh(HttpServletRequest request, HttpServletResponse response) {
        Optional<String> rawRefreshToken = CookieUtils.getCookie(request, JwtAuthenticationFilter.REFRESH_TOKEN_COOKIE_NAME)
                .map(Cookie::getValue);
        if (rawRefreshToken.isEmpty()) {
            // refresh 쿠키가 아예 없는 것도 AUTH_REFRESH_TOKEN_INVALID와 같은 성격의 확정 실패다 -
            // access 쿠키만 자연 만료 전까지 남아있으면(예: 다른 탭 로그아웃으로 refresh만 지워진
            // 상태) 반쪽 세션처럼 보일 수 있다. cross-origin 배포에서는 프론트가 이 httpOnly
            // 쿠키를 직접 지울 수 없으므로 여기서 지워야 한다.
            cookieUtils.deleteCookie(response, JwtAuthenticationFilter.ACCESS_TOKEN_COOKIE_NAME);
            throw new BusinessException(ErrorCode.AUTH_REFRESH_TOKEN_MISSING);
        }

        RefreshTokenService.RotationResult result;
        try {
            result = refreshTokenService.rotate(rawRefreshToken.get());
        } catch (BusinessException e) {
            // 확정적으로 무효한 토큰(AUTH_REFRESH_TOKEN_INVALID)일 때만 쿠키를 지운다 - Redis 장애로
            // 인한 AUTH_TOKEN_STORE_UNAVAILABLE(503)은 토큰이 실제로 무효한지 알 수 없는 상태이므로
            // 지우면 안 된다(장애가 걷힌 뒤에도 재로그인이 강제됨). cross-origin 배포에서는 프론트가
            // 이 쿠키들을 직접 지울 수 없으므로(백엔드 도메인 전용 httpOnly), 여기서 지워주지 않으면
            // 무효화된 refresh_token이 만료 시점까지 그대로 남아 매번 같은 401을 반복하게 된다. access
            // 쿠키도 함께 지운다 - 아직 자연 만료 전에 이 refresh가 불렸을 수도 있어(예: 다른 탭에서
            // 로그아웃), access만 남기면 그 쿠키가 만료될 때까지 반쪽 세션처럼 보일 수 있다.
            if (e.getErrorCode() == ErrorCode.AUTH_REFRESH_TOKEN_INVALID) {
                cookieUtils.deleteCookie(response, JwtAuthenticationFilter.ACCESS_TOKEN_COOKIE_NAME);
                cookieUtils.deleteCookie(response, JwtAuthenticationFilter.REFRESH_TOKEN_COOKIE_NAME);
            }
            throw e;
        }
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

        try {
            String refreshToken = refreshTokenService.issue(user);
            cookieUtils.addCookie(response, JwtAuthenticationFilter.REFRESH_TOKEN_COOKIE_NAME, refreshToken,
                    (int) refreshTokenService.getValiditySeconds());
        } catch (BusinessException e) {
            // refresh token 발급 실패(Redis 장애 등 AUTH_TOKEN_STORE_UNAVAILABLE) 시 access token
            // 쿠키만 남으면, 응답은 실패(503)인데 브라우저에는 30분짜리 반쪽 로그인 세션이 남는
            // 문제가 있다 - GlobalExceptionHandler는 새 ResponseEntity만 만들 뿐 이미 추가된
            // Set-Cookie 헤더는 지우지 않으므로, OAuth2AuthenticationSuccessHandler와 동일하게
            // 실패로 확정하는 이 경로에서 access 쿠키를 지워 깨끗한 상태로 되돌린다.
            cookieUtils.deleteCookie(response, JwtAuthenticationFilter.ACCESS_TOKEN_COOKIE_NAME);
            // 이번 로그인/가입 시도에서 새로 발급한 적은 없지만, 브라우저에 이전 세션의
            // refresh_token 쿠키가 그대로 남아있을 수 있다(재로그인 시도, 여러 탭 등) - 그대로
            // 두면 사용자는 "로그인 실패"를 봤는데 나중에 Redis가 복구된 뒤 그 옛 refresh_token으로
            // /auth/refresh가 조용히 성공해 예전 세션이 되살아나는 혼란스러운 상태가 된다. 실패로
            // 확정하는 이 경로에서 있을지 모르는 옛 refresh 쿠키도 함께 지워 깨끗한 상태로 만든다.
            cookieUtils.deleteCookie(response, JwtAuthenticationFilter.REFRESH_TOKEN_COOKIE_NAME);
            throw e;
        }
    }

    private MeResponse toMeResponse(User user) {
        return new MeResponse(
                user.getId(), user.getEmail(), user.getNickname(), user.getProfileImageUrl(), user.getRole().name());
    }
}
