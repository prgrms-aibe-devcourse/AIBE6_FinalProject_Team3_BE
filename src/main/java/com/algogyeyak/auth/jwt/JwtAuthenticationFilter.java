package com.algogyeyak.auth.jwt;

import com.algogyeyak.global.error.ErrorCode;
import com.algogyeyak.user.enums.Role;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

public class JwtAuthenticationFilter extends OncePerRequestFilter {

    public static final String ACCESS_TOKEN_COOKIE_NAME = "access_token";
    public static final String REFRESH_TOKEN_COOKIE_NAME = "refresh_token";

    // 이 필터는 예외를 던지지 않고 SecurityContext를 비워둔 채 다음 필터로 넘기기만 하므로,
    // "왜" 인증에 실패했는지(토큰 없음/무효/만료)는 이 요청 속성에 남겨야만 SecurityConfig의
    // authenticationEntryPoint가 나중에 읽어서 401 응답 코드를 구분해 내려줄 수 있다.
    public static final String AUTH_FAILURE_REASON_ATTRIBUTE = "auth.failureReason";

    private final JwtProvider jwtProvider;
    private final AccessTokenRevocationService accessTokenRevocationService;

    public JwtAuthenticationFilter(JwtProvider jwtProvider, AccessTokenRevocationService accessTokenRevocationService) {
        this.jwtProvider = jwtProvider;
        this.accessTokenRevocationService = accessTokenRevocationService;
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
        if (accessTokenRevocationService.isRevoked(claims.getId())) {
            request.setAttribute(AUTH_FAILURE_REASON_ATTRIBUTE, ErrorCode.AUTH_TOKEN_INVALID);
            return;
        }

        Long userId = Long.valueOf(claims.getSubject());
        String email = claims.get("email", String.class);
        Role role = Role.valueOf(claims.get("role", String.class));
        JwtUserPrincipal principal = new JwtUserPrincipal(userId, email, role);

        List<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
        var authentication = new UsernamePasswordAuthenticationToken(principal, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    // public static: AuthController.logout()도 정확히 이 규칙(헤더 우선, 쿠키 폴백)으로 access
    // token을 찾아야 한다 — 그래야 로그인 때 쓴 것과 같은 방식으로 인증한 요청이 로그아웃 때도
    // 같은 토큰을 무효화 대상으로 찾는다. 로직이 두 곳에 따로 있으면 한쪽만 고치고 다른 쪽을
    // 놓치는 사고(실제로 났었음 — logout이 쿠키만 보고 Authorization 헤더를 놓쳐 Bearer
    // 클라이언트는 로그아웃해도 access token이 무효화되지 않던 버그)가 재발하기 쉽다.
    public static String resolveToken(HttpServletRequest request) {
        String bearer = request.getHeader("Authorization");
        if (StringUtils.hasText(bearer) && bearer.startsWith("Bearer ")) {
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
