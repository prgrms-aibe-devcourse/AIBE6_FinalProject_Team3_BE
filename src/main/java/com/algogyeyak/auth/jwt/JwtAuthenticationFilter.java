package com.algogyeyak.auth.jwt;

import com.algogyeyak.user.enums.Role;
import io.jsonwebtoken.Claims;
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

        if (StringUtils.hasText(token) && jwtProvider.validateToken(token)) {
            Claims claims = jwtProvider.parseClaims(token);

            // 로그아웃으로 jti가 블랙리스트에 오른 access token은 서명/만료가 아직 유효해도
            // 인증 처리하지 않는다 — 로그아웃 즉시 무효화되어야 하는 이유는 팀 결정 사항 참고.
            if (!accessTokenRevocationService.isRevoked(claims.getId())) {
                Long userId = Long.valueOf(claims.getSubject());
                String email = claims.get("email", String.class);
                Role role = Role.valueOf(claims.get("role", String.class));
                JwtUserPrincipal principal = new JwtUserPrincipal(userId, email, role);

                List<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
                var authentication = new UsernamePasswordAuthenticationToken(principal, null, authorities);
                SecurityContextHolder.getContext().setAuthentication(authentication);
            }
        }

        filterChain.doFilter(request, response);
    }

    private String resolveToken(HttpServletRequest request) {
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
