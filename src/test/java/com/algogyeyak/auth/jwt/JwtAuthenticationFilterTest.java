package com.algogyeyak.auth.jwt;

import com.algogyeyak.global.error.ErrorCode;
import com.algogyeyak.user.enums.Role;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JwtAuthenticationFilterTest {

    private final JwtProvider jwtProvider =
            new JwtProvider("test-secret-key-must-be-at-least-32-bytes-long", 3600);
    private final RevokedAccessTokenRepository revokedAccessTokenRepository = mock(RevokedAccessTokenRepository.class);
    private final AccessTokenRevocationService accessTokenRevocationService =
            new AccessTokenRevocationService(revokedAccessTokenRepository);
    private final JwtAuthenticationFilter filter = new JwtAuthenticationFilter(jwtProvider, accessTokenRevocationService);

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void setsAuthenticationWhenValidTokenInCookie() throws Exception {
        String token = jwtProvider.createAccessToken(1L, "test@example.com", Role.USER);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new jakarta.servlet.http.Cookie(JwtAuthenticationFilter.ACCESS_TOKEN_COOKIE_NAME, token));
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain filterChain = mock(FilterChain.class);

        filter.doFilter(request, response, filterChain);

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        JwtUserPrincipal principal = (JwtUserPrincipal) authentication.getPrincipal();
        assertEquals(1L, principal.userId());
        assertEquals("test@example.com", principal.email());
        assertEquals(Role.USER, principal.role());
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void setsAuthenticationWhenValidTokenInAuthorizationHeader() throws Exception {
        String token = jwtProvider.createAccessToken(2L, "header@example.com", Role.USER);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain filterChain = mock(FilterChain.class);

        filter.doFilter(request, response, filterChain);

        JwtUserPrincipal principal =
                (JwtUserPrincipal) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        assertEquals(2L, principal.userId());
    }

    @Test
    void prefersAuthorizationHeaderOverCookieWhenBothPresent() throws Exception {
        String headerToken = jwtProvider.createAccessToken(10L, "header@example.com", Role.USER);
        String cookieToken = jwtProvider.createAccessToken(20L, "cookie@example.com", Role.USER);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + headerToken);
        request.setCookies(new jakarta.servlet.http.Cookie(JwtAuthenticationFilter.ACCESS_TOKEN_COOKIE_NAME, cookieToken));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, mock(FilterChain.class));

        JwtUserPrincipal principal =
                (JwtUserPrincipal) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        assertEquals(10L, principal.userId());
    }

    @Test
    void doesNotSetAuthenticationWhenNoTokenPresent() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain filterChain = mock(FilterChain.class);

        filter.doFilter(request, response, filterChain);

        assertNull(SecurityContextHolder.getContext().getAuthentication());
        assertEquals(ErrorCode.AUTH_TOKEN_MISSING, request.getAttribute(JwtAuthenticationFilter.AUTH_FAILURE_REASON_ATTRIBUTE));
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void doesNotSetAuthenticationWhenTokenInvalid() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new jakarta.servlet.http.Cookie(JwtAuthenticationFilter.ACCESS_TOKEN_COOKIE_NAME, "not-a-valid-token"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, mock(FilterChain.class));

        assertNull(SecurityContextHolder.getContext().getAuthentication());
        assertEquals(ErrorCode.AUTH_TOKEN_INVALID, request.getAttribute(JwtAuthenticationFilter.AUTH_FAILURE_REASON_ATTRIBUTE));
    }

    @Test
    void doesNotSetAuthenticationWhenTokenExpired() throws Exception {
        JwtProvider shortLivedProvider = new JwtProvider("test-secret-key-must-be-at-least-32-bytes-long", 0);
        String token = shortLivedProvider.createAccessToken(1L, "test@example.com", Role.USER);
        Thread.sleep(1100);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new jakarta.servlet.http.Cookie(JwtAuthenticationFilter.ACCESS_TOKEN_COOKIE_NAME, token));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, mock(FilterChain.class));

        assertNull(SecurityContextHolder.getContext().getAuthentication());
        assertEquals(ErrorCode.AUTH_TOKEN_EXPIRED, request.getAttribute(JwtAuthenticationFilter.AUTH_FAILURE_REASON_ATTRIBUTE));
    }

    @Test
    void doesNotSetAuthenticationWhenRoleClaimIsNotAValidEnumConstant() throws Exception {
        // 서명은 멀쩡하지만(같은 시크릿으로 직접 서명), role 클레임이 지금 코드의 Role enum에 없는
        // 값인 경우를 재현한다 - 예: 배포 중 enum 상수 이름이 바뀌기 전에 발급된 토큰. 이 경우도
        // 컨테이너 기본 500이 아니라 AUTH_TOKEN_INVALID(401)로 처리되어야 한다.
        SecretKey secretKey = Keys.hmacShaKeyFor(
                "test-secret-key-must-be-at-least-32-bytes-long".getBytes(StandardCharsets.UTF_8));
        Instant now = Instant.now();
        String token = Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject("1")
                .claim("email", "test@example.com")
                .claim("role", "SUPERADMIN")
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(3600)))
                .signWith(secretKey)
                .compact();

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new jakarta.servlet.http.Cookie(JwtAuthenticationFilter.ACCESS_TOKEN_COOKIE_NAME, token));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, mock(FilterChain.class));

        assertNull(SecurityContextHolder.getContext().getAuthentication());
        assertEquals(ErrorCode.AUTH_TOKEN_INVALID, request.getAttribute(JwtAuthenticationFilter.AUTH_FAILURE_REASON_ATTRIBUTE));
    }

    @Test
    void doesNotSetAuthenticationWhenTokenJtiIsRevoked() throws Exception {
        String token = jwtProvider.createAccessToken(1L, "test@example.com", Role.USER);
        Claims claims = jwtProvider.parseClaims(token);
        when(revokedAccessTokenRepository.existsById(claims.getId())).thenReturn(true);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new jakarta.servlet.http.Cookie(JwtAuthenticationFilter.ACCESS_TOKEN_COOKIE_NAME, token));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, mock(FilterChain.class));

        assertNull(SecurityContextHolder.getContext().getAuthentication());
        assertEquals(ErrorCode.AUTH_TOKEN_INVALID, request.getAttribute(JwtAuthenticationFilter.AUTH_FAILURE_REASON_ATTRIBUTE));
    }
}
