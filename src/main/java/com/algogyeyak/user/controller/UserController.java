package com.algogyeyak.user.controller;

import com.algogyeyak.user.dto.NicknameCheckResponse;
import com.algogyeyak.user.dto.NicknamePolicy;
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

    // 닉네임 정책(정규식/안내 문구)이 frontend에 하드코딩돼 있으면 두 곳이 어긋날 때 "프론트는 통과,
    // 서버는 거부"하는 이중 실패가 생긴다 - AuthController.passwordPolicy()와 같은 이유로, 회원가입/
    // 프로필 폼에서 클라이언트 측 선제 검증에 쓸 값을 여기서 그대로 내려준다. 로그인 전(회원가입
    // 폼)에도 필요해 인증 없이 열어둔다.
    public record NicknamePolicyResponse(String pattern, String message) {
    }

    @GetMapping("/nickname-policy")
    public ApiResponse<NicknamePolicyResponse> nicknamePolicy() {
        return ApiResponse.success(new NicknamePolicyResponse(NicknamePolicy.HTML_INPUT_PATTERN, NicknamePolicy.MESSAGE));
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
