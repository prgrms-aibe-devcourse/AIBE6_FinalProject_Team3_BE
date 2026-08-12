package com.algogyeyak.user.controller;

import com.algogyeyak.auth.service.SessionLogoutService;
import com.algogyeyak.user.dto.NicknameCheckResponse;
import com.algogyeyak.auth.jwt.JwtUserPrincipal;
import com.algogyeyak.global.exception.BusinessException;
import com.algogyeyak.global.s3.dto.PresignedUploadRequest;
import com.algogyeyak.global.s3.dto.PresignedUploadResponse;
import com.algogyeyak.user.dto.ProfileImageConfirmRequest;
import com.algogyeyak.user.dto.ProfileRegisterRequest;
import com.algogyeyak.user.dto.ProfileUpdateRequest;
import com.algogyeyak.user.dto.UserProfileResponse;
import com.algogyeyak.user.service.UserService;
import com.algogyeyak.global.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
public class UserController {

    private static final Logger log = LoggerFactory.getLogger(UserController.class);

    private final UserService userService;
    private final SessionLogoutService sessionLogoutService;

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
            @AuthenticationPrincipal JwtUserPrincipal userDetails,
            HttpServletRequest request,
            HttpServletResponse response) {
        userService.withdraw(userDetails.userId());

        // 탈퇴(데이터 익명화/정리)는 이미 커밋됐다 - 이후 세션 무효화가 Redis 장애로 실패해도
        // 탈퇴 자체를 되돌릴 수 없고, 그렇다고 503을 응답하면 클라이언트가 탈퇴 실패로 오인해
        // 재시도했다가 이번엔 이미 탈퇴된 상태라 404(USER_WITHDRAWN)를 받는 혼란만 남는다. 쿠키가
        // 안 지워져도 JwtAuthenticationFilter가 다음 요청부터 탈퇴 상태를 다시 확인해 차단하므로
        // 보안 구멍은 아니다 - 로그만 남기고 탈퇴 자체는 성공으로 응답한다.
        try {
            sessionLogoutService.logout(request, response);
        } catch (BusinessException e) {
            log.warn("회원 탈퇴 후 세션 무효화 실패 - 탈퇴 자체는 성공 처리합니다. userId={}", userDetails.userId(), e);
        }

        return ApiResponse.successWithoutData();
    }
}
