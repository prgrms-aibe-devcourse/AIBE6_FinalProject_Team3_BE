package com.algogyeyak.auth.controller;

import com.algogyeyak.auth.jwt.JwtAuthenticationFilter;
import com.algogyeyak.auth.jwt.JwtUserPrincipal;
import com.algogyeyak.auth.oauth.CookieUtils;
import com.algogyeyak.global.response.ApiResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private final CookieUtils cookieUtils;

    public AuthController(CookieUtils cookieUtils) {
        this.cookieUtils = cookieUtils;
    }

    public record MeResponse(Long userId, String email, String role) {
    }

    @GetMapping("/me")
    public ResponseEntity<ApiResponse<MeResponse>> me(@AuthenticationPrincipal JwtUserPrincipal principal) {
        MeResponse body = new MeResponse(principal.userId(), principal.email(), principal.role().name());
        return ResponseEntity.ok(ApiResponse.success(body));
    }

    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout(HttpServletResponse response) {
        cookieUtils.deleteCookie(response, JwtAuthenticationFilter.ACCESS_TOKEN_COOKIE_NAME);
        return ResponseEntity.ok(ApiResponse.successWithoutData());
    }
}
