package com.algogyeyak.user.controller;

import com.algogyeyak.auth.service.SessionLogoutService;
import com.algogyeyak.contractanalysis.dto.ContractHistoryDetailResponse;
import com.algogyeyak.contractanalysis.dto.ContractHistoryResponse;
import com.algogyeyak.contractanalysis.service.ContractAnalysisHistoryService;
import com.algogyeyak.user.dto.NicknameCheckResponse;
import com.algogyeyak.user.dto.NicknamePolicy;
import com.algogyeyak.auth.jwt.JwtUserPrincipal;
import com.algogyeyak.global.exception.BusinessException;
import com.algogyeyak.global.response.PageResponse;
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
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
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
    private final ContractAnalysisHistoryService contractAnalysisHistoryService;

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

    // 정렬(최신순)은 항상 고정이라 sort 쿼리 파라미터는 받지 않는다(보내도 서비스에서 무시함) -
    // page/size만 다른 목록 API와 동일한 기본값(20, 최대 100)으로 받는다.
    @GetMapping("/me/contract-history")
    public ApiResponse<PageResponse<ContractHistoryResponse>> getMyContractHistory(
            @AuthenticationPrincipal JwtUserPrincipal userDetails,
            @PageableDefault(size = 20) Pageable pageable
    ) {
        return ApiResponse.success(contractAnalysisHistoryService.listMyContractHistory(userDetails.userId(), pageable));
    }

    // 목록(/me/contract-history)은 clauses를 포함하지 않아 가볍게 유지하고, 항목 클릭 시에만
    // 이 엔드포인트로 riskFlag/explanation/question/suggestedText를 조회한다.
    @GetMapping("/me/contract-history/{id}")
    public ApiResponse<ContractHistoryDetailResponse> getMyContractHistoryDetail(
            @AuthenticationPrincipal JwtUserPrincipal userDetails,
            @PathVariable Long id
    ) {
        return ApiResponse.success(contractAnalysisHistoryService.getMyContractHistoryDetail(userDetails.userId(), id));
    }
}
