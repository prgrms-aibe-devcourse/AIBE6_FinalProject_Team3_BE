package com.algogyeyak.riskanalysis.dto;

import com.algogyeyak.riskanalysis.entity.PropertyRisk;
import com.algogyeyak.riskanalysis.entity.PropertyRiskCheck;
import com.algogyeyak.riskanalysis.enums.RiskCheckReason;
import com.algogyeyak.riskanalysis.enums.RiskCheckStatus;
import com.algogyeyak.riskanalysis.enums.RiskSignalType;

import java.time.LocalDateTime;
import java.util.List;

public record RiskSignalResponse(
        RiskSignalType signalType,
        RiskCheckStatus status,
        RiskCheckReason reason,
        String description,
        List<String> recommendedActions,
        LocalDateTime checkedAt
) {
    // 신호 타입별 후속 행동 안내 - 매물마다 달라지는 내용이 아니라 신호 자체에 붙는 일반적인
    // 조언이라 DB에 저장하지 않고 응답 매핑 시점에 정적으로 붙인다. "왜 저렴한지 확인해보세요"
    // 라는 뭉뚱그린 한 줄로는 실제 행동으로 이어지기 어렵다는 피드백에 따라, 체크리스트 형태로
    // 분리해 프론트가 별도 목록으로 렌더링할 수 있게 한다(2026-08-20). 등기부등본/실거래가 비교/
    // 중개사 문의는 이 앱이 직접 지원하는 기능이 아니라 사용자가 앱 밖에서 해야 하는 행동이지만,
    // 그래도 "무엇을 확인해야 하는지" 구체적으로 짚어주는 것 자체가 피드백의 요구사항이다.
    private static final List<String> PRICE_ANOMALY_ACTIONS = List.of(
            "동일 지역 최근 실거래가 확인",
            "등기부등본 확인",
            "주변 동일 면적 매물 가격 비교",
            "중개사에게 가격이 낮은 이유 확인",
            "옵션/관리비 등 추가 비용 확인"
    );

    // risk는 PropertyRisk가 없을 수 있다(판정 불가/실패거나, 성공했지만 리스크가 발견되지 않은 경우).
    // 리스크가 실제로 발견된 경우에만 후속 행동 안내를 붙인다 - 리스크가 없으면 안내할 이유도 없다.
    public static RiskSignalResponse from(PropertyRiskCheck check, PropertyRisk risk) {
        return new RiskSignalResponse(
                check.getSignalType(),
                check.getStatus(),
                check.getReason(),
                risk != null ? risk.getDescription() : null,
                risk != null ? recommendedActionsFor(check.getSignalType()) : List.of(),
                check.getCheckedAt()
        );
    }

    private static List<String> recommendedActionsFor(RiskSignalType signalType) {
        return signalType == RiskSignalType.PRICE_ANOMALY ? PRICE_ANOMALY_ACTIONS : List.of();
    }
}
