package com.algogyeyak.user.controller;

import com.algogyeyak.user.dto.NicknameCheckResponse;
import com.algogyeyak.user.dto.ProfileRegisterRequest;
import com.algogyeyak.user.dto.ProfileUpdateRequest;
import com.algogyeyak.user.dto.UserProfileResponse;
import com.algogyeyak.user.service.UserService;
import com.algogyeyak.global.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @GetMapping("/me")
    public ApiResponse<UserProfileResponse> getMyProfile(
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        return ApiResponse.success(userService.getMyProfile(userDetails.getUserId()));
    }

    @GetMapping("/nickname-check")
    public ApiResponse<NicknameCheckResponse> checkNickname(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @RequestParam String nickname) {
        return ApiResponse.success(userService.checkNicknameAvailable(userDetails.getUserId(), nickname));
    }

    @PostMapping("/me/profile")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<UserProfileResponse> registerProfile(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @Valid @RequestBody ProfileRegisterRequest request) {
        return ApiResponse.success(userService.registerProfile(userDetails.getUserId(), request));
    }

    @PatchMapping("/me")
    public ApiResponse<UserProfileResponse> updateMyProfile(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @Valid @RequestBody ProfileUpdateRequest request) {
        return ApiResponse.success(userService.updateMyProfile(userDetails.getUserId(), request));
    }

    @DeleteMapping("/me")
    public ApiResponse<Void> withdraw(
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        userService.withdraw(userDetails.getUserId());
        return ApiResponse.successWithoutData();
    }
}
