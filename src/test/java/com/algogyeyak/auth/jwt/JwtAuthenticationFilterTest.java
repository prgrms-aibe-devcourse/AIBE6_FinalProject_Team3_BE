package com.algogyeyak.auth.jwt;

import com.algogyeyak.global.error.ErrorCode;
import com.algogyeyak.user.entity.User;
import com.algogyeyak.user.enums.Role;
import com.algogyeyak.user.repository.UserRepository;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class JwtAuthenticationFilterTest {

    private final JwtProvider jwtProvider =
            new JwtProvider("test-secret-key-must-be-at-least-32-bytes-long", 3600);
    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final AccessTokenRevocationService accessTokenRevocationService =
            new AccessTokenRevocationService(redisTemplate);
    private final UserRepository userRepository = mock(UserRepository.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
    private final UserAuthStatusCacheService userAuthStatusCacheService =
            new UserAuthStatusCacheService(redisTemplate);
    private final JwtAuthenticationFilter filter =
            new JwtAuthenticationFilter(jwtProvider, accessTokenRevocationService, userRepository, userAuthStatusCacheService);

    JwtAuthenticationFilterTest() {
        // 캐시는 기본적으로 미스로 두어(get()이 null 반환) 기존 테스트가 전부 DB 경로를 그대로
        // 타도록 한다 - 캐시 적중 시나리오는 별도 테스트에서 명시적으로 stub한다.
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

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
        awaitTokenExpiry(token);

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
    void doesNotSetAuthenticationWhenTokenWasIssuedBeforePasswordChanged() throws Exception {
        // 회귀 테스트 - 비밀번호가 바뀐(재설정 또는 본인 변경) 뒤에는, 서명/만료가 아직 유효한
        // access token이라도 그 변경 이전에 발급된 것이면 거부해야 한다. 그러지 않으면 탈취됐거나
        // 다른 기기에 열려 있던 access token이 만료 전까지 계속 유효한 상태로 남는다.
        String token = jwtProvider.createAccessToken(1L, "test@example.com", Role.USER);
        User user = activeUser(1L, "test@example.com", Role.USER);
        ReflectionTestUtils.setField(user, "passwordChangedAt", LocalDateTime.now().plusSeconds(5));
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new jakarta.servlet.http.Cookie(JwtAuthenticationFilter.ACCESS_TOKEN_COOKIE_NAME, token));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, mock(FilterChain.class));

        assertNull(SecurityContextHolder.getContext().getAuthentication());
        assertEquals(ErrorCode.AUTH_TOKEN_INVALID, request.getAttribute(JwtAuthenticationFilter.AUTH_FAILURE_REASON_ATTRIBUTE));
    }

    @Test
    void setsAuthenticationWhenTokenWasIssuedAfterPasswordChanged() throws Exception {
        User user = activeUser(1L, "test@example.com", Role.USER);
        ReflectionTestUtils.setField(user, "passwordChangedAt", LocalDateTime.now().minusSeconds(5));
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        String token = jwtProvider.createAccessToken(1L, "test@example.com", Role.USER);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new jakarta.servlet.http.Cookie(JwtAuthenticationFilter.ACCESS_TOKEN_COOKIE_NAME, token));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, mock(FilterChain.class));

        JwtUserPrincipal principal =
                (JwtUserPrincipal) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        assertEquals(1L, principal.userId());
    }

    @Test
    void failsClosedWithStoreUnavailableWhenRevocationCheckThrows() throws Exception {
        // 회귀 테스트 - Redis 장애로 isRevoked()가 예전엔 "블랙리스트에 있음"(true)과 똑같이
        // 처리돼 AUTH_TOKEN_INVALID(401)로 응답했다. 진짜 로그아웃된 토큰과 장애 상황을 구분해
        // failsClosedWithStoreUnavailableWhenDbLookupThrows()와 동일하게 503으로 응답해야 한다.
        String token = jwtProvider.createAccessToken(1L, "test@example.com", Role.USER);
        Claims claims = jwtProvider.parseClaims(token);
        when(redisTemplate.hasKey("auth:revoked-access-token:" + claims.getId()))
                .thenThrow(new org.springframework.dao.QueryTimeoutException("redis down"));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new jakarta.servlet.http.Cookie(JwtAuthenticationFilter.ACCESS_TOKEN_COOKIE_NAME, token));
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain filterChain = mock(FilterChain.class);

        filter.doFilter(request, response, filterChain);

        assertNull(SecurityContextHolder.getContext().getAuthentication());
        assertEquals(ErrorCode.AUTH_TOKEN_STORE_UNAVAILABLE, request.getAttribute(JwtAuthenticationFilter.AUTH_FAILURE_REASON_ATTRIBUTE));
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

    @Test
    void skipsTokenProcessingEntirelyForPublicPaths() throws Exception {
        // shouldNotFilter() 회귀 테스트 - permitAll이면서 로그인 여부와 무관하게 항상 같은
        // 경로(/auth/login 등)는 토큰이 유효해도 Redis/DB 조회 자체가 발생하면 안 된다.
        String token = jwtProvider.createAccessToken(1L, "test@example.com", Role.USER);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/auth/login");
        request.setCookies(new jakarta.servlet.http.Cookie(JwtAuthenticationFilter.ACCESS_TOKEN_COOKIE_NAME, token));
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain filterChain = mock(FilterChain.class);

        filter.doFilter(request, response, filterChain);

        assertNull(SecurityContextHolder.getContext().getAuthentication());
        assertNull(request.getAttribute(JwtAuthenticationFilter.AUTH_FAILURE_REASON_ATTRIBUTE));
        verifyNoInteractions(userRepository);
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void skipsTokenProcessingForBasePathWithoutTrailingSlash() throws Exception {
        // 회귀 테스트 - SecurityConfig의 "/v3/api-docs/**" 패턴은 트레일링 슬래시 없는 베이스
        // 경로 자체("/v3/api-docs", springdoc이 실제로 쓰는 정확한 경로)도 permitAll로 매칭한다.
        // shouldNotFilter()가 단순 접두사(prefix + "/") 비교만 했다면 이 베이스 경로 자체는
        // 스킵되지 않아 그때마다 불필요한 Redis/DB 조회가 발생했다.
        String token = jwtProvider.createAccessToken(1L, "test@example.com", Role.USER);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/v3/api-docs");
        request.setCookies(new jakarta.servlet.http.Cookie(JwtAuthenticationFilter.ACCESS_TOKEN_COOKIE_NAME, token));
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain filterChain = mock(FilterChain.class);

        filter.doFilter(request, response, filterChain);

        assertNull(SecurityContextHolder.getContext().getAuthentication());
        assertNull(request.getAttribute(JwtAuthenticationFilter.AUTH_FAILURE_REASON_ATTRIBUTE));
        verifyNoInteractions(userRepository);
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void doesNotHitDbWhenUserStatusCacheHits() throws Exception {
        // UserAuthStatusCacheService 캐시 적중 시 DB(userRepository)를 아예 건드리지 않아야 한다.
        String token = jwtProvider.createAccessToken(1L, "test@example.com", Role.ADMIN);
        UserAuthStatusCacheService.CachedUserStatus cached = new UserAuthStatusCacheService.CachedUserStatus(
                "test@example.com", Role.ADMIN, com.algogyeyak.user.enums.UserStatus.ACTIVE,
                "캐시닉네임", null, null);
        when(valueOperations.get("auth:user-status:1"))
                .thenReturn(new com.fasterxml.jackson.databind.ObjectMapper()
                        .findAndRegisterModules()
                        .writeValueAsString(cached));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new jakarta.servlet.http.Cookie(JwtAuthenticationFilter.ACCESS_TOKEN_COOKIE_NAME, token));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, mock(FilterChain.class));

        JwtUserPrincipal principal =
                (JwtUserPrincipal) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        assertEquals("캐시닉네임", principal.nickname());
        assertEquals(Role.ADMIN, principal.role());
        verify(userRepository, never()).findById(any());
    }

    @Test
    void populatesUserStatusCacheOnCacheMiss() throws Exception {
        // 캐시 미스로 DB를 읽은 뒤에는 다음 요청이 캐시를 쓸 수 있도록 결과를 저장해야 한다.
        String token = jwtProvider.createAccessToken(1L, "test@example.com", Role.USER);
        when(userRepository.findById(1L)).thenReturn(Optional.of(activeUser(1L, "test@example.com", Role.USER)));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new jakarta.servlet.http.Cookie(JwtAuthenticationFilter.ACCESS_TOKEN_COOKIE_NAME, token));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, mock(FilterChain.class));

        verify(valueOperations).set(
                org.mockito.ArgumentMatchers.eq("auth:user-status:1"),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq(java.time.Duration.ofSeconds(30)));
    }

    // 고정 sleep(1100ms)은 느린 CI/컨테이너 등에서 실제 만료보다 먼저 깨어날 수 있어 드물게
    // 흔들린다 - RefreshTokenServiceRedisIntegrationTest.awaitKeyAbsence()와 동일하게, "얼마나
    // 기다릴지" 추측하는 대신 토큰이 실제로 만료됐는지(파싱이 실제로 실패하는지)를 짧은 간격으로
    // 직접 확인(polling)한다.
    private void awaitTokenExpiry(String token) throws InterruptedException {
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(10);
        while (System.currentTimeMillis() < deadline) {
            try {
                jwtProvider.parseClaims(token);
            } catch (JwtException e) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("토큰이 TTL로 자연 만료되지 않았다: " + token);
    }
}
