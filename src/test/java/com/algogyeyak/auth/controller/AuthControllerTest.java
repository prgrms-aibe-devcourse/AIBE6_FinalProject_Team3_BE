package com.algogyeyak.auth.controller;

import com.algogyeyak.auth.jwt.JwtAuthenticationFilter;
import com.algogyeyak.auth.jwt.JwtProvider;
import com.algogyeyak.user.entity.Role;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtProvider jwtProvider;

    @Test
    void meReturnsCurrentUserWithValidAccessTokenCookie() throws Exception {
        String token = jwtProvider.createAccessToken(1L, "test@example.com", Role.USER);

        mockMvc.perform(get("/auth/me")
                        .cookie(new Cookie(JwtAuthenticationFilter.ACCESS_TOKEN_COOKIE_NAME, token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.userId").value(1))
                .andExpect(jsonPath("$.data.email").value("test@example.com"))
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
    void logoutClearsCookieForAuthenticatedUser() throws Exception {
        String token = jwtProvider.createAccessToken(1L, "test@example.com", Role.USER);

        mockMvc.perform(post("/auth/logout")
                        .cookie(new Cookie(JwtAuthenticationFilter.ACCESS_TOKEN_COOKIE_NAME, token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }
}
