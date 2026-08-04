package com.algogyeyak.riskanalysis.controller;

import com.algogyeyak.auth.jwt.JwtUserPrincipal;
import com.algogyeyak.global.response.ApiResponse;
import com.algogyeyak.riskanalysis.dto.RiskSignalResponse;
import com.algogyeyak.riskanalysis.service.FakeListingSignalService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class RiskAnalysisController {

    private final FakeListingSignalService fakeListingSignalService;

    /**
     * 매물의 허위매물 의심 신호를 판정·저장한다. 최초 실행/재계산을 구분하지 않는다 -
     * FakeListingSignalService.checkAndSave()가 이미 upsert 구조라 몇 번을 호출해도 결과가 같다.
     */
    @PostMapping("/properties/{propertyId}/risk-analysis")
    public ApiResponse<Void> checkRiskSignals(
            @AuthenticationPrincipal JwtUserPrincipal userDetails,
            @PathVariable Long propertyId
    ) {
        fakeListingSignalService.checkAndSave(userDetails.userId(), propertyId);
        return ApiResponse.successWithoutData();
    }

    /**
     * 매물의 신호 4종 현재 상태를 조회한다.
     */
    @GetMapping("/properties/{propertyId}/risk-signals")
    public ApiResponse<List<RiskSignalResponse>> getRiskSignals(
            @AuthenticationPrincipal JwtUserPrincipal userDetails,
            @PathVariable Long propertyId
    ) {
        return ApiResponse.success(fakeListingSignalService.getSignals(userDetails.userId(), propertyId));
    }
}
