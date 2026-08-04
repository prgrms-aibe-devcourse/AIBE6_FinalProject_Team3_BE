package com.algogyeyak.riskanalysis.controller;

import com.algogyeyak.auth.jwt.JwtUserPrincipal;
import com.algogyeyak.global.response.ApiResponse;
import com.algogyeyak.riskanalysis.dto.RiskAnalysisSummaryResponse;
import com.algogyeyak.riskanalysis.dto.RiskSignalListResponse;
import com.algogyeyak.riskanalysis.service.FakeListingSignalService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class RiskAnalysisController {

    private final FakeListingSignalService fakeListingSignalService;

    /**
     * 매물의 허위매물 의심 신호를 판정·저장하고, 리스크가 발견된 신호 개수 요약을 바로 반환한다.
     * 최초 실행/재계산을 구분하지 않는다 - FakeListingSignalService.checkAndSave()가 이미 upsert
     * 구조라 몇 번을 호출해도 결과가 같다. 신호 상세 목록은 GET /risk-signals가 담당한다.
     */
    @PostMapping("/properties/{propertyId}/risk-analysis")
    public ApiResponse<RiskAnalysisSummaryResponse> checkRiskSignals(
            @AuthenticationPrincipal JwtUserPrincipal userDetails,
            @PathVariable Long propertyId
    ) {
        return ApiResponse.success(fakeListingSignalService.checkAndSummarize(userDetails.userId(), propertyId));
    }

    /**
     * 매물의 신호 4종 현재 상태를 조회한다.
     */
    @GetMapping("/properties/{propertyId}/risk-signals")
    public ApiResponse<RiskSignalListResponse> getRiskSignals(
            @AuthenticationPrincipal JwtUserPrincipal userDetails,
            @PathVariable Long propertyId
    ) {
        return ApiResponse.success(fakeListingSignalService.getSignals(userDetails.userId(), propertyId));
    }
}
