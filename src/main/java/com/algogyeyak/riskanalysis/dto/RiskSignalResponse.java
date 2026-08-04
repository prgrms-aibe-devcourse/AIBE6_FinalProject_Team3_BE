package com.algogyeyak.riskanalysis.dto;

import com.algogyeyak.riskanalysis.entity.PropertyRisk;
import com.algogyeyak.riskanalysis.entity.PropertyRiskCheck;
import com.algogyeyak.riskanalysis.enums.RiskCheckReason;
import com.algogyeyak.riskanalysis.enums.RiskCheckStatus;
import com.algogyeyak.riskanalysis.enums.RiskSignalType;

import java.time.LocalDateTime;

public record RiskSignalResponse(
        RiskSignalType signalType,
        RiskCheckStatus status,
        RiskCheckReason reason,
        String description,
        LocalDateTime checkedAt
) {
    // risk는 PropertyRisk가 없을 수 있다(판정 불가/실패거나, 성공했지만 리스크가 발견되지 않은 경우).
    public static RiskSignalResponse from(PropertyRiskCheck check, PropertyRisk risk) {
        return new RiskSignalResponse(
                check.getSignalType(),
                check.getStatus(),
                check.getReason(),
                risk != null ? risk.getDescription() : null,
                check.getCheckedAt()
        );
    }
}
