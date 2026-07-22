package com.algogyeyak.auth.controller;

import com.algogyeyak.auth.jwt.JwtAuthenticationFilter;
import com.algogyeyak.auth.jwt.JwtUserPrincipal;
import com.algogyeyak.auth.oauth.CookieUtils;
import com.algogyeyak.global.response.ApiResponse;
import com.algogyeyak.user.entity.User;
import com.algogyeyak.user.repository.UserRepository;
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
    private final UserRepository userRepository;

    public AuthController(CookieUtils cookieUtils, UserRepository userRepository) {
        this.cookieUtils = cookieUtils;
        this.userRepository = userRepository;
    }

    public record MeResponse(Long userId, String email, String nickname, String profileImageUrl, String role) {
    }

    @GetMapping("/me")
    public ResponseEntity<ApiResponse<MeResponse>> me(@AuthenticationPrincipal JwtUserPrincipal principal) {
        User user = userRepository.findById(principal.userId()).orElse(null);
        String nickname = user != null ? user.getNickname() : null;
        String profileImageUrl = user != null ? user.getProfileImageUrl() : null;
        MeResponse body = new MeResponse(
                principal.userId(), principal.email(), nickname, profileImageUrl, principal.role().name());
        return ResponseEntity.ok(ApiResponse.success(body));
    }

    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout(HttpServletResponse response) {
        cookieUtils.deleteCookie(response, JwtAuthenticationFilter.ACCESS_TOKEN_COOKIE_NAME);
        return ResponseEntity.ok(ApiResponse.successWithoutData());
    }
}
