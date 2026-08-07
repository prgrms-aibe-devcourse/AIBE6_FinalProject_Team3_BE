package com.algogyeyak.user.controller;

import com.algogyeyak.user.dto.NicknameCheckResponse;
import com.algogyeyak.auth.jwt.JwtUserPrincipal;
import com.algogyeyak.global.s3.dto.PresignedUploadRequest;
import com.algogyeyak.global.s3.dto.PresignedUploadResponse;
import com.algogyeyak.user.dto.ProfileImageConfirmRequest;
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
            @AuthenticationPrincipal JwtUserPrincipal userDetails) {
        return ApiResponse.success(userService.getMyProfile(userDetails.userId()));
    }

    // 회원가입 화면(로그인 전)에서도 호출되는 permitAll 경로라 userDetails가 null일 수 있다 -
    // 비로그인 요청은 익명 principal(문자열)이라 JwtUserPrincipal로 캐스팅이 안 돼 null이 주입된다.
    @GetMapping("/nickname-check")
    public ApiResponse<NicknameCheckResponse> checkNickname(
            @AuthenticationPrincipal JwtUserPrincipal userDetails,
            @RequestParam String nickname) {
        Long userId = userDetails != null ? userDetails.userId() : null;
        return ApiResponse.success(userService.checkNicknameAvailable(userId, nickname));
    }

    @PostMapping("/me/profile")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<UserProfileResponse> registerProfile(
            @AuthenticationPrincipal JwtUserPrincipal userDetails,
            @Valid @RequestBody ProfileRegisterRequest request) {
        return ApiResponse.success(userService.registerProfile(userDetails.userId(), request));
    }

    @PostMapping("/me/profile-image/presign")
    public ApiResponse<PresignedUploadResponse> presignProfileImage(
            @AuthenticationPrincipal JwtUserPrincipal userDetails,
            @Valid @RequestBody PresignedUploadRequest request) {
        return ApiResponse.success(userService.presignProfileImageUpload(userDetails.userId(), request));
    }

    @PostMapping("/me/profile-image/confirm")
    public ApiResponse<UserProfileResponse> confirmProfileImage(
            @AuthenticationPrincipal JwtUserPrincipal userDetails,
            @Valid @RequestBody ProfileImageConfirmRequest request) {
        return ApiResponse.success(userService.confirmProfileImageUpload(userDetails.userId(), request.getKey()));
    }

    @DeleteMapping("/me/profile-image")
    public ApiResponse<UserProfileResponse> resetProfileImage(
            @AuthenticationPrincipal JwtUserPrincipal userDetails) {
        return ApiResponse.success(userService.resetProfileImage(userDetails.userId()));
    }

    @PatchMapping("/me")
    public ApiResponse<UserProfileResponse> updateMyProfile(
            @AuthenticationPrincipal JwtUserPrincipal userDetails,
            @Valid @RequestBody ProfileUpdateRequest request) {
        return ApiResponse.success(userService.updateMyProfile(userDetails.userId(), request));
    }

    @DeleteMapping("/me")
    public ApiResponse<Void> withdraw(
            @AuthenticationPrincipal JwtUserPrincipal userDetails) {
        userService.withdraw(userDetails.userId());
        return ApiResponse.successWithoutData();
    }
}
