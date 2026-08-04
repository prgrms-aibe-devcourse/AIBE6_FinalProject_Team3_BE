package com.algogyeyak.riskanalysis.controller;

import com.algogyeyak.auth.jwt.JwtUserPrincipal;
import com.algogyeyak.global.response.ApiResponse;
import com.algogyeyak.riskanalysis.dto.DepositSafetyCheckResponse;
import com.algogyeyak.riskanalysis.service.DepositSafetyCheckService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class DepositSafetyController {

    private final DepositSafetyCheckService depositSafetyCheckService;

    /**
     * 매물의 보증금 안전성(전세가율) 체크 결과를 조회한다. 조회 전용 - 계산은 POST /risk-analysis가
     * 트리거한다(연동은 아직 안 돼 있음, risk-analysis-design.md 참고).
     */
    @GetMapping("/properties/{propertyId}/deposit-safety")
    public ApiResponse<DepositSafetyCheckResponse> getDepositSafety(
            @AuthenticationPrincipal JwtUserPrincipal userDetails,
            @PathVariable Long propertyId
    ) {
        return ApiResponse.success(depositSafetyCheckService.get(userDetails.userId(), propertyId));
    }
}
