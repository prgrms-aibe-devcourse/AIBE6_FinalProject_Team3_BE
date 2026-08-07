package com.algogyeyak.auth.jwt;

import com.algogyeyak.global.error.ErrorCode;
import com.algogyeyak.user.entity.User;
import com.algogyeyak.user.enums.Role;
import com.algogyeyak.user.repository.UserRepository;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JwtAuthenticationFilterTest {

    private final JwtProvider jwtProvider =
            new JwtProvider("test-secret-key-must-be-at-least-32-bytes-long", 3600);
    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final AccessTokenRevocationService accessTokenRevocationService =
            new AccessTokenRevocationService(redisTemplate);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final JwtAuthenticationFilter filter =
            new JwtAuthenticationFilter(jwtProvider, accessTokenRevocationService, userRepository);

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private User activeUser(Long id, String email, Role role) {
        User user = User.createOAuthUser(email, "테스트유저", null);
        ReflectionTestUtils.setField(user, "id", id);
        ReflectionTestUtils.setField(user, "role", role);
        return user;
    }

    @Test
    void setsAuthenticationWhenValidTokenInCookie() throws Exception {
        String token = jwtProvider.createAccessToken(1L, "test@example.com", Role.USER);
        when(userRepository.findById(1L)).thenReturn(Optional.of(activeUser(1L, "test@example.com", Role.USER)));
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
        when(userRepository.findById(2L)).thenReturn(Optional.of(activeUser(2L, "header@example.com", Role.USER)));
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
        when(userRepository.findById(10L)).thenReturn(Optional.of(activeUser(10L, "header@example.com", Role.USER)));
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
    void doesNotSetAuthenticationWhenUnderlyingUserNoLongerExists() throws Exception {
        // 토큰 서명/만료는 멀쩡해도 DB에서 유저 자체가 사라진 경우(탈퇴 후 완전삭제 등) - 컨테이너
        // 기본 500이 아니라 AUTH_TOKEN_INVALID(401)로 처리되어야 한다.
        String token = jwtProvider.createAccessToken(1L, "test@example.com", Role.USER);
        when(userRepository.findById(1L)).thenReturn(Optional.empty());

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new jakarta.servlet.http.Cookie(JwtAuthenticationFilter.ACCESS_TOKEN_COOKIE_NAME, token));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, mock(FilterChain.class));

        assertNull(SecurityContextHolder.getContext().getAuthentication());
        assertEquals(ErrorCode.AUTH_TOKEN_INVALID, request.getAttribute(JwtAuthenticationFilter.AUTH_FAILURE_REASON_ATTRIBUTE));
    }

    @Test
    void doesNotSetAuthenticationWhenUserHasBeenSuspendedSinceTokenWasIssued() throws Exception {
        // 관리자가 유저를 정지시킨 뒤에도 기존 access token이 만료 전까지 계속 통하던 버그의
        // 회귀 테스트 - 토큰 발급 시점엔 정상이었어도 매 요청 DB 재조회로 즉시 차단되어야 한다.
        String token = jwtProvider.createAccessToken(1L, "test@example.com", Role.USER);
        User user = activeUser(1L, "test@example.com", Role.USER);
        user.suspend();
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new jakarta.servlet.http.Cookie(JwtAuthenticationFilter.ACCESS_TOKEN_COOKIE_NAME, token));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, mock(FilterChain.class));

        assertNull(SecurityContextHolder.getContext().getAuthentication());
        assertEquals(ErrorCode.AUTH_TOKEN_INVALID, request.getAttribute(JwtAuthenticationFilter.AUTH_FAILURE_REASON_ATTRIBUTE));
    }

    @Test
    void doesNotSetAuthenticationWhenUserHasWithdrawnSinceTokenWasIssued() throws Exception {
        String token = jwtProvider.createAccessToken(1L, "test@example.com", Role.USER);
        User user = activeUser(1L, "test@example.com", Role.USER);
        user.withdraw();
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new jakarta.servlet.http.Cookie(JwtAuthenticationFilter.ACCESS_TOKEN_COOKIE_NAME, token));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, mock(FilterChain.class));

        assertNull(SecurityContextHolder.getContext().getAuthentication());
        assertEquals(ErrorCode.AUTH_TOKEN_INVALID, request.getAttribute(JwtAuthenticationFilter.AUTH_FAILURE_REASON_ATTRIBUTE));
    }

    @Test
    void usesCurrentDbRoleInsteadOfStaleTokenRoleClaim() throws Exception {
        // 토큰 발급 시점엔 USER였지만 그 사이 관리자로 승격된 경우 - 토큰을 재발급받지 않아도
        // 이번 요청부터 바로 ROLE_ADMIN 권한으로 인증되어야 한다.
        String token = jwtProvider.createAccessToken(1L, "test@example.com", Role.USER);
        when(userRepository.findById(1L)).thenReturn(Optional.of(activeUser(1L, "test@example.com", Role.ADMIN)));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new jakarta.servlet.http.Cookie(JwtAuthenticationFilter.ACCESS_TOKEN_COOKIE_NAME, token));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, mock(FilterChain.class));

        JwtUserPrincipal principal =
                (JwtUserPrincipal) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        assertEquals(Role.ADMIN, principal.role());
    }

    @Test
    void failsClosedWithStoreUnavailableWhenDbLookupThrows() throws Exception {
        // 회귀 테스트 - DB 장애로 findById()가 예외를 던지면 catch 없이 그대로 새어나가 컨테이너
        // 기본 500(ApiResponse 포맷이 아님)이 되던 문제. AccessTokenRevocationService의 Redis
        // 장애 처리와 동일하게 fail-closed(AUTH_TOKEN_STORE_UNAVAILABLE, 503)로 처리해야 한다.
        String token = jwtProvider.createAccessToken(1L, "test@example.com", Role.USER);
        when(userRepository.findById(1L)).thenThrow(new org.springframework.dao.QueryTimeoutException("DB timeout"));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new jakarta.servlet.http.Cookie(JwtAuthenticationFilter.ACCESS_TOKEN_COOKIE_NAME, token));
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain filterChain = mock(FilterChain.class);

        filter.doFilter(request, response, filterChain);

        assertNull(SecurityContextHolder.getContext().getAuthentication());
        assertEquals(ErrorCode.AUTH_TOKEN_STORE_UNAVAILABLE, request.getAttribute(JwtAuthenticationFilter.AUTH_FAILURE_REASON_ATTRIBUTE));
        // 예외를 삼키고 필터 체인은 계속 진행해야 SecurityConfig의 authenticationEntryPoint가
        // 이 속성을 읽어 503 응답을 만들 수 있다 - 여기서 예외가 그대로 전파되면 그 기회조차 없다.
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void doesNotSetAuthenticationWhenTokenJtiIsRevoked() throws Exception {
        String token = jwtProvider.createAccessToken(1L, "test@example.com", Role.USER);
        Claims claims = jwtProvider.parseClaims(token);
        when(redisTemplate.hasKey("auth:revoked-access-token:" + claims.getId())).thenReturn(true);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new jakarta.servlet.http.Cookie(JwtAuthenticationFilter.ACCESS_TOKEN_COOKIE_NAME, token));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, mock(FilterChain.class));

        assertNull(SecurityContextHolder.getContext().getAuthentication());
        assertEquals(ErrorCode.AUTH_TOKEN_INVALID, request.getAttribute(JwtAuthenticationFilter.AUTH_FAILURE_REASON_ATTRIBUTE));
    }
}
