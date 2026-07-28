package com.algogyeyak.auth.controller;

import com.algogyeyak.auth.jwt.JwtAuthenticationFilter;
import com.algogyeyak.auth.jwt.JwtProvider;
import com.algogyeyak.auth.token.RefreshTokenService;
import com.algogyeyak.global.error.ErrorCode;
import com.algogyeyak.global.exception.BusinessException;
import com.algogyeyak.user.enums.AuthProvider;
import com.algogyeyak.user.enums.Role;
import com.algogyeyak.user.entity.User;
import com.algogyeyak.user.repository.UserRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtProvider jwtProvider;

    @Autowired
    private AuthController authController;

    @MockitoBean
    private UserRepository userRepository;

    @MockitoBean
    private RefreshTokenService refreshTokenService;

    @MockitoBean
    private PasswordEncoder passwordEncoder;

    @Test
    void signupCreatesUserAndIssuesAuthCookies() throws Exception {
        when(userRepository.existsByEmail("new@example.com")).thenReturn(false);
        when(userRepository.existsByNickname("새유저")).thenReturn(false);
        when(passwordEncoder.encode("password1")).thenReturn("encoded-hash");
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            ReflectionTestUtils.setField(user, "id", 1L);
            return user;
        });
        when(refreshTokenService.issue(any(User.class))).thenReturn("new-refresh-token");
        when(refreshTokenService.getValiditySeconds()).thenReturn(1209600L);

        mockMvc.perform(post("/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"new@example.com","password":"password1","nickname":"새유저"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.email").value("new@example.com"))
                .andExpect(jsonPath("$.data.nickname").value("새유저"))
                .andExpect(header().string("Set-Cookie",
                        containsString(JwtAuthenticationFilter.ACCESS_TOKEN_COOKIE_NAME + "=")))
                .andExpect(header().stringValues("Set-Cookie", hasItem(containsString("new-refresh-token"))));
    }

    @Test
    void signupRejectsDuplicateEmail() throws Exception {
        when(userRepository.existsByEmail("dup@example.com")).thenReturn(true);

        mockMvc.perform(post("/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"dup@example.com","password":"password1","nickname":"닉네임"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("AUTH_EMAIL_ALREADY_EXISTS"));
    }

    @Test
    void signupRejectsInvalidPayload() throws Exception {
        mockMvc.perform(post("/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"not-an-email","password":"short","nickname":"닉"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("BAD_REQUEST"));
    }

    @Test
    void loginSucceedsAndIssuesAuthCookies() throws Exception {
        User user = User.createLocalUser("test@example.com", "encoded-hash", "테스트유저");
        ReflectionTestUtils.setField(user, "id", 1L);
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("password1", "encoded-hash")).thenReturn(true);
        when(refreshTokenService.issue(user)).thenReturn("new-refresh-token");
        when(refreshTokenService.getValiditySeconds()).thenReturn(1209600L);

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"test@example.com","password":"password1"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.email").value("test@example.com"))
                .andExpect(header().string("Set-Cookie",
                        containsString(JwtAuthenticationFilter.ACCESS_TOKEN_COOKIE_NAME + "=")));
    }

    @Test
    void loginRejectsWrongPassword() throws Exception {
        User user = User.createLocalUser("test@example.com", "encoded-hash", "테스트유저");
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong", "encoded-hash")).thenReturn(false);

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"test@example.com","password":"wrong"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTH_INVALID_CREDENTIALS"));
    }

    @Test
    void loginRejectsUnknownEmail() throws Exception {
        when(userRepository.findByEmail("unknown@example.com")).thenReturn(Optional.empty());

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"unknown@example.com","password":"password1"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTH_INVALID_CREDENTIALS"));
    }

    @Test
    void updatePasswordRejectsRequestWithoutToken() throws Exception {
        mockMvc.perform(patch("/auth/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"newPassword":"newPassword1"}
                                """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void updatePasswordSucceedsForOAuthOnlyAccountWithoutCurrentPassword() throws Exception {
        String token = jwtProvider.createAccessToken(1L, "social@example.com", Role.USER);
        User user = User.createOAuthUser("social@example.com", "소셜유저", null, AuthProvider.KAKAO, "999");
        ReflectionTestUtils.setField(user, "id", 1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(passwordEncoder.encode("newPassword1")).thenReturn("new-encoded-hash");

        mockMvc.perform(patch("/auth/password")
                        .cookie(new Cookie(JwtAuthenticationFilter.ACCESS_TOKEN_COOKIE_NAME, token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"newPassword":"newPassword1"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        assertEquals("new-encoded-hash", user.getPasswordHash());
    }

    @Test
    void updatePasswordRejectsWrongCurrentPassword() throws Exception {
        String token = jwtProvider.createAccessToken(1L, "test@example.com", Role.USER);
        User user = User.createLocalUser("test@example.com", "encoded-hash", "테스트유저");
        ReflectionTestUtils.setField(user, "id", 1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong-current", "encoded-hash")).thenReturn(false);

        mockMvc.perform(patch("/auth/password")
                        .cookie(new Cookie(JwtAuthenticationFilter.ACCESS_TOKEN_COOKIE_NAME, token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword":"wrong-current","newPassword":"newPassword1"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTH_INVALID_CREDENTIALS"));
    }

    @Test
    void updatePasswordRejectsInvalidNewPasswordFormat() throws Exception {
        String token = jwtProvider.createAccessToken(1L, "test@example.com", Role.USER);

        mockMvc.perform(patch("/auth/password")
                        .cookie(new Cookie(JwtAuthenticationFilter.ACCESS_TOKEN_COOKIE_NAME, token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"newPassword":"short"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("BAD_REQUEST"));
    }

    @Test
    void devLoginReturnsNotFoundWhenDisabled() throws Exception {
        // application.yml 기본값(DEV_LOGIN_ENABLED 미설정)이라 테스트 컨텍스트에서는 항상 꺼져 있어야 한다 —
        // 운영에서 이 스위치가 꺼진 채로 배포됐을 때와 동일한 상황을 별도 설정 없이 검증한다.
        mockMvc.perform(post("/auth/dev-login"))
                .andExpect(status().isNotFound());
    }

    @Test
    void devLoginIssuesAuthCookiesForSeededAdminWhenEnabled() throws Exception {
        ReflectionTestUtils.setField(authController, "devLoginEnabled", true);
        ReflectionTestUtils.setField(authController, "devLoginEmail", "admin@algogyeyak.local");
        try {
            // AdminAccountSeeder가 실제로 만드는 시드 계정과 동일하게 passwordHash 없이 구성한다 —
            // dev-login은 비밀번호를 검사하지 않는다.
            User admin = User.createLocalUser("admin@algogyeyak.local", null, "관리자");
            ReflectionTestUtils.setField(admin, "id", 1L);
            ReflectionTestUtils.setField(admin, "role", Role.ADMIN);
            when(userRepository.findByEmail("admin@algogyeyak.local")).thenReturn(Optional.of(admin));
            when(refreshTokenService.issue(admin)).thenReturn("new-refresh-token");
            when(refreshTokenService.getValiditySeconds()).thenReturn(1209600L);

            mockMvc.perform(post("/auth/dev-login"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.role").value("ADMIN"))
                    .andExpect(header().string("Set-Cookie",
                            containsString(JwtAuthenticationFilter.ACCESS_TOKEN_COOKIE_NAME + "=")));
        } finally {
            // 다른 테스트에 영향이 없도록 기본값(false)으로 되돌린다.
            ReflectionTestUtils.setField(authController, "devLoginEnabled", false);
        }
    }

    @Test
    void devLoginNormalizesConfiguredEmailBeforeLookup() throws Exception {
        ReflectionTestUtils.setField(authController, "devLoginEnabled", true);
        ReflectionTestUtils.setField(authController, "devLoginEmail", "  Admin@Algogyeyak.Local  ");
        try {
            User admin = User.createLocalUser("admin@algogyeyak.local", null, "관리자");
            ReflectionTestUtils.setField(admin, "id", 1L);
            ReflectionTestUtils.setField(admin, "role", Role.ADMIN);
            when(userRepository.findByEmail("admin@algogyeyak.local")).thenReturn(Optional.of(admin));
            when(refreshTokenService.issue(admin)).thenReturn("new-refresh-token");
            when(refreshTokenService.getValiditySeconds()).thenReturn(1209600L);

            mockMvc.perform(post("/auth/dev-login"))
                    .andExpect(status().isOk());
        } finally {
            ReflectionTestUtils.setField(authController, "devLoginEnabled", false);
        }
    }

    @Test
    void devLoginReturnsNotFoundWhenEnabledButSeededAdminMissing() throws Exception {
        ReflectionTestUtils.setField(authController, "devLoginEnabled", true);
        ReflectionTestUtils.setField(authController, "devLoginEmail", "admin@algogyeyak.local");
        try {
            when(userRepository.findByEmail("admin@algogyeyak.local")).thenReturn(Optional.empty());

            mockMvc.perform(post("/auth/dev-login"))
                    .andExpect(status().isNotFound());
        } finally {
            ReflectionTestUtils.setField(authController, "devLoginEnabled", false);
        }
    }

    @Test
    void devLoginReturnsNotFoundWhenAccountAtConfiguredEmailIsNotAdmin() throws Exception {
        ReflectionTestUtils.setField(authController, "devLoginEnabled", true);
        ReflectionTestUtils.setField(authController, "devLoginEmail", "admin@algogyeyak.local");
        try {
            // 예: 어떤 이유로 이 이메일에 일반 USER 계정이 걸려 있는 경우 — dev-login이 그 계정으로
            // 로그인시키면 안 되므로 Role.ADMIN이 아니면 "찾을 수 없음"과 동일하게 취급한다.
            User notAdmin = User.createLocalUser("admin@algogyeyak.local", null, "일반유저");
            when(userRepository.findByEmail("admin@algogyeyak.local")).thenReturn(Optional.of(notAdmin));

            mockMvc.perform(post("/auth/dev-login"))
                    .andExpect(status().isNotFound());
        } finally {
            ReflectionTestUtils.setField(authController, "devLoginEnabled", false);
        }
    }

    @Test
    void devLoginReturnsNotFoundWhenSeededAdminHasBeenWithdrawn() throws Exception {
        ReflectionTestUtils.setField(authController, "devLoginEnabled", true);
        ReflectionTestUtils.setField(authController, "devLoginEmail", "admin@algogyeyak.local");
        try {
            User admin = User.createLocalUser("admin@algogyeyak.local", null, "관리자");
            admin.grantAdminRole();
            admin.withdraw();
            when(userRepository.findByEmail("admin@algogyeyak.local")).thenReturn(Optional.of(admin));

            mockMvc.perform(post("/auth/dev-login"))
                    .andExpect(status().isNotFound());
        } finally {
            ReflectionTestUtils.setField(authController, "devLoginEnabled", false);
        }
    }

    @Test
    void meReturnsCurrentUserWithValidAccessTokenCookie() throws Exception {
        String token = jwtProvider.createAccessToken(1L, "test@example.com", Role.USER);
        User user = User.createOAuthUser(
                "test@example.com", "테스트유저", "https://example.com/avatar.png", AuthProvider.KAKAO, "123");
        when(userRepository.findById(eq(1L))).thenReturn(Optional.of(user));

        mockMvc.perform(get("/auth/me")
                        .cookie(new Cookie(JwtAuthenticationFilter.ACCESS_TOKEN_COOKIE_NAME, token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.userId").value(1))
                .andExpect(jsonPath("$.data.email").value("test@example.com"))
                .andExpect(jsonPath("$.data.nickname").value("테스트유저"))
                .andExpect(jsonPath("$.data.profileImageUrl").value("https://example.com/avatar.png"))
                .andExpect(jsonPath("$.data.role").value("USER"));
    }

    @Test
    void meRejectsRequestWithoutToken() throws Exception {
        mockMvc.perform(get("/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    void meRejectsWhenUnderlyingUserNoLongerExists() throws Exception {
        String token = jwtProvider.createAccessToken(1L, "test@example.com", Role.USER);
        when(userRepository.findById(eq(1L))).thenReturn(Optional.empty());

        mockMvc.perform(get("/auth/me")
                        .cookie(new Cookie(JwtAuthenticationFilter.ACCESS_TOKEN_COOKIE_NAME, token)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    void meRejectsWhenUserHasWithdrawn() throws Exception {
        String token = jwtProvider.createAccessToken(1L, "test@example.com", Role.USER);
        User user = User.createOAuthUser(
                "test@example.com", "테스트유저", "https://example.com/avatar.png", AuthProvider.KAKAO, "123");
        user.withdraw();
        when(userRepository.findById(eq(1L))).thenReturn(Optional.of(user));

        mockMvc.perform(get("/auth/me")
                        .cookie(new Cookie(JwtAuthenticationFilter.ACCESS_TOKEN_COOKIE_NAME, token)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    void logoutClearsCookieForAuthenticatedUser() throws Exception {
        String token = jwtProvider.createAccessToken(1L, "test@example.com", Role.USER);

        mockMvc.perform(post("/auth/logout")
                        .cookie(new Cookie(JwtAuthenticationFilter.ACCESS_TOKEN_COOKIE_NAME, token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void logoutSucceedsEvenWithoutValidToken() throws Exception {
        // 토큰이 만료/위조되었거나 아예 없어도 로그아웃(쿠키 삭제)은 항상 가능해야 한다.
        mockMvc.perform(post("/auth/logout"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(refreshTokenService, never()).revoke(any());
    }

    @Test
    void accessTokenIsRejectedAfterLogout() throws Exception {
        String token = jwtProvider.createAccessToken(1L, "test@example.com", Role.USER);
        User user = User.createOAuthUser(
                "test@example.com", "테스트유저", "https://example.com/avatar.png", AuthProvider.KAKAO, "123");
        when(userRepository.findById(eq(1L))).thenReturn(Optional.of(user));

        mockMvc.perform(post("/auth/logout")
                        .cookie(new Cookie(JwtAuthenticationFilter.ACCESS_TOKEN_COOKIE_NAME, token)))
                .andExpect(status().isOk());

        // 로그아웃 전에는 유효했던 바로 그 access token이, 만료 전인데도 더 이상 통과하지 않아야 한다 —
        // 로그아웃해도 access token이 자연 만료 전까지 계속 유효했던 원래 버그의 회귀 테스트.
        mockMvc.perform(get("/auth/me")
                        .cookie(new Cookie(JwtAuthenticationFilter.ACCESS_TOKEN_COOKIE_NAME, token)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    void accessTokenIsRejectedAfterLogoutViaBearerHeader() throws Exception {
        // Swagger/Postman처럼 쿠키 없이 Authorization: Bearer 헤더만으로 인증하는 클라이언트의
        // 회귀 테스트 — logout()이 access_token 쿠키만 보고 있던 예전 버전에서는 이 access token이
        // 블랙리스트에 오르지 않아, 로그아웃 후에도 만료 전까지 계속 유효했다.
        String token = jwtProvider.createAccessToken(1L, "test@example.com", Role.USER);
        User user = User.createOAuthUser(
                "test@example.com", "테스트유저", "https://example.com/avatar.png", AuthProvider.KAKAO, "123");
        when(userRepository.findById(eq(1L))).thenReturn(Optional.of(user));

        mockMvc.perform(post("/auth/logout")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        mockMvc.perform(get("/auth/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    void logoutRevokesRefreshTokenWhenCookiePresent() throws Exception {
        mockMvc.perform(post("/auth/logout")
                        .cookie(new Cookie(JwtAuthenticationFilter.REFRESH_TOKEN_COOKIE_NAME, "raw-refresh-token")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(refreshTokenService).revoke("raw-refresh-token");
    }

    @Test
    void refreshRejectsRequestWithoutRefreshTokenCookie() throws Exception {
        mockMvc.perform(post("/auth/refresh"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    void refreshIssuesNewAccessAndRefreshTokenCookiesForValidToken() throws Exception {
        User user = User.createOAuthUser("test@example.com", "테스트유저", "http://img", AuthProvider.KAKAO, "123");
        ReflectionTestUtils.setField(user, "id", 1L);
        when(refreshTokenService.rotate("old-refresh-token"))
                .thenReturn(new RefreshTokenService.RotationResult(user, "new-refresh-token"));
        when(refreshTokenService.getValiditySeconds()).thenReturn(1209600L);

        mockMvc.perform(post("/auth/refresh")
                        .cookie(new Cookie(JwtAuthenticationFilter.REFRESH_TOKEN_COOKIE_NAME, "old-refresh-token")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(header().string("Set-Cookie",
                        containsString(JwtAuthenticationFilter.ACCESS_TOKEN_COOKIE_NAME + "=")))
                .andExpect(header().stringValues("Set-Cookie", hasItem(containsString("new-refresh-token"))));
    }

    @Test
    void refreshRejectsInvalidOrExpiredToken() throws Exception {
        when(refreshTokenService.rotate("bad-token"))
                .thenThrow(new BusinessException(ErrorCode.UNAUTHORIZED, "유효하지 않은 Refresh Token입니다."));

        mockMvc.perform(post("/auth/refresh")
                        .cookie(new Cookie(JwtAuthenticationFilter.REFRESH_TOKEN_COOKIE_NAME, "bad-token")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.message").value("유효하지 않은 Refresh Token입니다."));
    }
}
