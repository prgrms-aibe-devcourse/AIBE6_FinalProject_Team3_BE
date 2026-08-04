package com.algogyeyak.riskanalysis.dto;

import com.algogyeyak.riskanalysis.enums.RiskCheckReason;
import com.algogyeyak.riskanalysis.enums.RiskCheckStatus;

public record SignalCheckResult(
        RiskCheckStatus status,
        RiskCheckReason reason, // status=SUCCESS면 null
        String description // 리스크로 판정된 경우의 설명 1건, 리스크 없음/판정 불가/실패면 null
) {
    public static SignalCheckResult success(String description) {
        return new SignalCheckResult(RiskCheckStatus.SUCCESS, null, description);
    }

    public static SignalCheckResult undeterminable(RiskCheckReason reason) {
        return new SignalCheckResult(RiskCheckStatus.UNDETERMINABLE, reason, null);
    }

    public static SignalCheckResult failed(RiskCheckReason reason) {
        return new SignalCheckResult(RiskCheckStatus.FAILED, reason, null);
    }
}
