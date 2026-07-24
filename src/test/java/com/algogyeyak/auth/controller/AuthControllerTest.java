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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
                .andExpect(jsonPath("$.error.code").value("COMMON_400"));
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
                .andExpect(jsonPath("$.error.code").value("COMMON_401"));
    }

    @Test
    void meRejectsWhenUnderlyingUserNoLongerExists() throws Exception {
        String token = jwtProvider.createAccessToken(1L, "test@example.com", Role.USER);
        when(userRepository.findById(eq(1L))).thenReturn(Optional.empty());

        mockMvc.perform(get("/auth/me")
                        .cookie(new Cookie(JwtAuthenticationFilter.ACCESS_TOKEN_COOKIE_NAME, token)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("COMMON_401"));
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
                .andExpect(jsonPath("$.error.code").value("COMMON_401"));
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
                .andExpect(jsonPath("$.error.code").value("COMMON_401"));
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
