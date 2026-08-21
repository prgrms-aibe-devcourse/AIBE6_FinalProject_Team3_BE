package com.algogyeyak.auth.jwt;

import com.algogyeyak.global.error.ErrorCode;
import com.algogyeyak.global.exception.BusinessException;
import com.algogyeyak.user.entity.User;
import com.algogyeyak.user.repository.UserRepository;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    public static final String ACCESS_TOKEN_COOKIE_NAME = "access_token";
    public static final String REFRESH_TOKEN_COOKIE_NAME = "refresh_token";

    // 이 필터는 예외를 던지지 않고 SecurityContext를 비워둔 채 다음 필터로 넘기기만 하므로,
    // "왜" 인증에 실패했는지(토큰 없음/무효/만료)는 이 요청 속성에 남겨야만 SecurityConfig의
    // authenticationEntryPoint가 나중에 읽어서 401 응답 코드를 구분해 내려줄 수 있다.
    public static final String AUTH_FAILURE_REASON_ATTRIBUTE = "auth.failureReason";

    // SecurityConfig의 permitAll 목록 중 "로그인 여부와 무관하게 항상 같은 방식으로 동작하는"
    // 경로만 여기 넣는다 - 이 필터 자체를 건너뛰므로 토큰이 있어도 Redis(revocation)/DB(User) 조회가
    // 아예 발생하지 않는다. /users/nickname-check 등 "로그인했으면 본인 제외 처리가 달라지는"
    // permitAll 경로는 일부러 넣지 않는다 - 그 경로들은 인증 자체는 선택이지만 SecurityContext는
    // 채워져 있어야 하므로 필터를 계속 타야 한다.
    private static final List<String> SKIP_EXACT_PATHS = List.of(
            "/auth/logout", "/auth/refresh",
            "/auth/signup", "/auth/login", "/auth/dev-login",
            "/auth/password-policy",
            "/actuator/health", "/actuator/prometheus");
    // SecurityConfig의 "base/**" 패턴과 동일한 의미다 - Ant/PathPattern에서 "**"는 빈 세그먼트도
    // 매칭하므로 "base" 자체와 "base/..." 둘 다 permitAll이다(CsrfHeaderFilter의 H2 콘솔 처리와
    // 동일한 이유). 예를 들어 springdoc의 실제 스펙 엔드포인트는 트레일링 슬래시 없는
    // "/v3/api-docs" 자체라, 단순 접두사(prefix + "/") 비교만 하면 이 경로가 스킵 대상에서
    // 빠져 그때마다 불필요한 Redis/DB 조회가 발생한다.
    private static final List<String> SKIP_PATH_BASES = List.of(
            "/oauth2", "/login",
            "/swagger-ui", "/v3/api-docs", "/h2-console",
            "/auth/email-verification", "/auth/password-reset");

    private final JwtProvider jwtProvider;
    private final AccessTokenRevocationService accessTokenRevocationService;
    private final UserRepository userRepository;
    private final UserAuthStatusCacheService userAuthStatusCacheService;

    public JwtAuthenticationFilter(
            JwtProvider jwtProvider,
            AccessTokenRevocationService accessTokenRevocationService,
            UserRepository userRepository,
            UserAuthStatusCacheService userAuthStatusCacheService) {
        this.jwtProvider = jwtProvider;
        this.accessTokenRevocationService = accessTokenRevocationService;
        this.userRepository = userRepository;
        this.userAuthStatusCacheService = userAuthStatusCacheService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return SKIP_EXACT_PATHS.contains(path)
                || SKIP_PATH_BASES.stream().anyMatch(base -> path.equals(base) || path.startsWith(base + "/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String token = resolveToken(request);

        if (!StringUtils.hasText(token)) {
            request.setAttribute(AUTH_FAILURE_REASON_ATTRIBUTE, ErrorCode.AUTH_TOKEN_MISSING);
        } else {
            authenticate(request, token);
        }

        filterChain.doFilter(request, response);
    }

    private void authenticate(HttpServletRequest request, String token) {
        Claims claims;
        try {
            claims = jwtProvider.parseClaims(token);
        } catch (ExpiredJwtException e) {
            request.setAttribute(AUTH_FAILURE_REASON_ATTRIBUTE, ErrorCode.AUTH_TOKEN_EXPIRED);
            return;
        } catch (JwtException | IllegalArgumentException e) {
            request.setAttribute(AUTH_FAILURE_REASON_ATTRIBUTE, ErrorCode.AUTH_TOKEN_INVALID);
            return;
        }

        // 로그아웃으로 jti가 블랙리스트에 오른 access token은 서명/만료가 아직 유효해도
        // 인증 처리하지 않는다 — 로그아웃 즉시 무효화되어야 하는 이유는 팀 결정 사항 참고.
        // 재사용 불가능해졌다는 점에서 "무효한 토큰"과 같은 사유로 취급한다.
        boolean revoked;
        try {
            revoked = accessTokenRevocationService.isRevoked(claims.getId());
        } catch (BusinessException e) {
            // Redis 장애로 블랙리스트 여부를 확신할 수 없는 경우다(AccessTokenRevocationService
            // 참고) - 진짜 무효화된 토큰(401)과 구분해 저장소 장애(503)로 응답해야, 클라이언트가
            // "로그아웃됨"이 아니라 "잠시 후 다시 시도"로 정확히 안내받는다.
            request.setAttribute(AUTH_FAILURE_REASON_ATTRIBUTE, e.getErrorCode());
            return;
        }
        if (revoked) {
            request.setAttribute(AUTH_FAILURE_REASON_ATTRIBUTE, ErrorCode.AUTH_TOKEN_INVALID);
            return;
        }

        Long userId;
        try {
            userId = Long.valueOf(claims.getSubject());
        } catch (IllegalArgumentException | NullPointerException e) {
            request.setAttribute(AUTH_FAILURE_REASON_ATTRIBUTE, ErrorCode.AUTH_TOKEN_INVALID);
            return;
        }

        // 토큰의 email/role 클레임은 발급 시점 스냅샷이라, 그 이후 관리자가 계정을 정지시키거나
        // 권한을 바꿔도(관리자 페이지) 토큰이 자연 만료되기 전까지는 반영되지 않는 문제가 있었다.
        // isRevoked()가 이미 매 요청 Redis 블랙리스트 조회를 하므로, 그 비용에 얹어 User를 다시
        // 조회해 최신 상태/권한을 신뢰의 원천으로 삼는다 - AuthController.me()가 매 요청 User를
        // 다시 읽어오는 것과 같은 이유다. DB를 매번 때리는 비용을 줄이려고 UserAuthStatusCacheService가
        // 30초 TTL로 캐싱한 결과를 먼저 보고, 캐시 미스일 때만 DB로 폴백한다.
        UserAuthStatusCacheService.CachedUserStatus userStatus = userAuthStatusCacheService.find(userId).orElse(null);
        if (userStatus == null) {
            User user;
            try {
                user = userRepository.findById(userId).orElse(null);
            } catch (DataAccessException e) {
                // isRevoked()의 Redis 장애 처리(AccessTokenRevocationService)와 같은 이유로 DB 장애도
                // fail-closed여야 한다 - 여기서 조용히 넘어가면 "권한을 확인할 수 없음"을 어느 쪽으로도
                // 오인하지 않고 컨테이너 기본 500(ApiResponse 포맷이 아닌 원시 에러 페이지)으로 새어나가
                // 버렸었다. isRevoked()가 이미 이 요청 속성 하나로 401/503을 구분해 응답하는 경로가
                // 있으므로, 같은 성격(저장소 장애)의 AUTH_TOKEN_STORE_UNAVAILABLE을 그대로 재사용한다.
                log.error("DB 장애로 인증 처리 중 사용자 조회 실패 — fail-closed로 503 처리합니다. userId={}", userId, e);
                request.setAttribute(AUTH_FAILURE_REASON_ATTRIBUTE, ErrorCode.AUTH_TOKEN_STORE_UNAVAILABLE);
                return;
            }
            if (user == null) {
                request.setAttribute(AUTH_FAILURE_REASON_ATTRIBUTE, ErrorCode.AUTH_TOKEN_INVALID);
                return;
            }
            userStatus = UserAuthStatusCacheService.CachedUserStatus.from(user);
            userAuthStatusCacheService.save(userId, user);
        }

        if (userStatus.isWithdrawn() || userStatus.isSuspended()) {
            request.setAttribute(AUTH_FAILURE_REASON_ATTRIBUTE, ErrorCode.AUTH_TOKEN_INVALID);
            return;
        }

        // 비밀번호가 바뀐 뒤(본인 변경 또는 비밀번호 재설정) 그 이전에 발급된 access token은
        // 서명/만료가 아직 유효해도 거부한다 - 그러지 않으면 이미 탈취됐거나 다른 기기에 열려
        // 있던 access token이 최대 만료 시간(access-token-validity-seconds)까지 계속 유효하게
        // 남는다. 발급 시각(iat)은 매 요청 새로 만들지 않으므로 클록 스큐 문제 없이 서버 자체
        // 시계로만 비교한다(passwordChangedAt도 같은 서버에서 LocalDateTime.now()로 찍힘).
        //
        // 알려진 한계: passwordChangedAt은 초 단위로 절삭 저장된다(User.updatePasswordHash 참고,
        // 비밀번호 변경 직후 같은 초에 발급된 새 토큰이 오탐 401을 받던 버그의 수정). 그 결과
        // "변경 직전 같은 초에 발급된 토큰"과 "변경 직후 같은 초에 발급된 토큰"은 초 단위 비교로는
        // 구분이 불가능하다 - isBefore를 등호 포함(<=)으로 바꾸면 이 좁은 우회는 막히지만 방금 고친
        // 오탐 401 버그가 그대로 재발한다. JWT의 iat 자체가 초 단위 정밀도라 근본적으로 완전히
        // 닫을 수 없는 트레이드오프이므로, 가용성(오탐 401 방지)을 우선한 현재 동작을 의도적으로
        // 유지한다.
        //
        // 추가 한계(캐시 도입 이후): userStatus가 캐시 적중이었다면 passwordChangedAt 자체가 최대
        // 30초 지난 값일 수 있다 - 그 창 안에서는 "방금 바뀐 비밀번호"를 이 필터가 아직 모른 채로
        // 통과시킬 수 있다(UserAuthStatusCacheService 클래스 주석 참고, 의도적으로 감수).
        if (userStatus.passwordChangedAt() != null && claims.getIssuedAt() != null) {
            LocalDateTime issuedAt = LocalDateTime.ofInstant(claims.getIssuedAt().toInstant(), ZoneId.systemDefault());
            if (issuedAt.isBefore(userStatus.passwordChangedAt())) {
                request.setAttribute(AUTH_FAILURE_REASON_ATTRIBUTE, ErrorCode.AUTH_TOKEN_INVALID);
                return;
            }
        }

        JwtUserPrincipal principal = new JwtUserPrincipal(
                userId, userStatus.email(), userStatus.role(), userStatus.nickname(), userStatus.profileImageUrl());

        List<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("ROLE_" + userStatus.role().name()));
        var authentication = new UsernamePasswordAuthenticationToken(principal, null, authorities);
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
    }

    // public static: AuthController.logout()도 정확히 이 규칙(헤더 우선, 쿠키 폴백)으로 access
    // token을 찾아야 한다 — 그래야 로그인 때 쓴 것과 같은 방식으로 인증한 요청이 로그아웃 때도
    // 같은 토큰을 무효화 대상으로 찾는다. 로직이 두 곳에 따로 있으면 한쪽만 고치고 다른 쪽을
    // 놓치는 사고(실제로 났었음 — logout이 쿠키만 보고 Authorization 헤더를 놓쳐 Bearer
    // 클라이언트는 로그아웃해도 access token이 무효화되지 않던 버그)가 재발하기 쉽다.
    public static String resolveToken(HttpServletRequest request) {
        String bearer = request.getHeader("Authorization");
        // RFC 7235상 auth-scheme 토큰(Bearer)은 대소문자를 구분하지 않는다 - startsWith("Bearer ")만
        // 검사하면 스펙을 준수해 소문자 "bearer "로 보내는 클라이언트가 유효한 토큰을 들고도 이
        // 검사를 통과 못 해 쿠키 폴백으로 떨어지고, 쿠키가 없으면 AUTH_TOKEN_MISSING으로 막힌다.
        if (StringUtils.hasText(bearer) && bearer.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return bearer.substring(7);
        }

        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (ACCESS_TOKEN_COOKIE_NAME.equals(cookie.getName())) {
                    return cookie.getValue();
                }
            }
        }

        return null;
    }
}
