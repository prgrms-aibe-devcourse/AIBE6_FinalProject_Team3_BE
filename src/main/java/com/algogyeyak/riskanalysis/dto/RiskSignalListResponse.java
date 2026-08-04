package com.algogyeyak.riskanalysis.dto;

import java.util.List;

/**
 * 매물의 위험 신호 4종 현재 상태 조회(GET /risk-signals) 응답. 신호별 상태(SUCCESS/UNDETERMINABLE/FAILED)를
 * 그대로 노출하되, 리스크가 실제로 발견된 신호 개수를 signalCount로 함께 제공한다.
 */
public record RiskSignalListResponse(
        Long propertyId,
        int signalCount,
        List<RiskSignalResponse> signals,
        String disclaimer
) {
    public static final String DISCLAIMER = "확정 판단이 아닌 참고용 정보이며, 법률·등기 검토를 대체하지 않습니다.";
}
