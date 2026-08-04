package com.algogyeyak.riskanalysis.controller;

import com.algogyeyak.auth.jwt.JwtUserPrincipal;
import com.algogyeyak.global.response.ApiResponse;
import com.algogyeyak.riskanalysis.dto.DepositSafetyCheckResponse;
import com.algogyeyak.riskanalysis.dto.DepositSafetyRecalculateRequest;
import com.algogyeyak.riskanalysis.service.DepositSafetyCheckService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class DepositSafetyController {

    private final DepositSafetyCheckService depositSafetyCheckService;

    /**
     * 매물의 보증금 안전성(전세가율) 체크 결과를 조회한다. 조회 전용 - 계산은 POST /risk-analysis가
     * 함께 트리거한다(FakeListingSignalService.checkAndSave() 참고).
     */
    @GetMapping("/properties/{propertyId}/deposit-safety")
    public ApiResponse<DepositSafetyCheckResponse> getDepositSafety(
            @AuthenticationPrincipal JwtUserPrincipal userDetails,
            @PathVariable Long propertyId
    ) {
        return ApiResponse.success(depositSafetyCheckService.get(userDetails.userId(), propertyId));
    }

    /**
     * 사용자가 입력한 선순위보증금(+근저당 채권최고액)을 반영해 전세가율을 정밀 재계산하고,
     * 갱신된 결과를 바로 반환한다.
     */
    @PostMapping("/properties/{propertyId}/deposit-safety/recalculate")
    public ApiResponse<DepositSafetyCheckResponse> recalculateDepositSafety(
            @AuthenticationPrincipal JwtUserPrincipal userDetails,
            @PathVariable Long propertyId,
            @Valid @RequestBody DepositSafetyRecalculateRequest request
    ) {
        return ApiResponse.success(depositSafetyCheckService.recalculate(
                userDetails.userId(), propertyId, request.seniorDeposit(), request.maxClaimAmount()));
    }
}
