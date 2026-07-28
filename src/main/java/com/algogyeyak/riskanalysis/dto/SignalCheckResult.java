package com.algogyeyak.riskanalysis.dto;

import com.algogyeyak.riskanalysis.enums.RiskCheckReason;
import com.algogyeyak.riskanalysis.enums.RiskCheckStatus;

import java.util.List;

public record SignalCheckResult(
        RiskCheckStatus status,
        RiskCheckReason reason, // status=SUCCESS면 null
        List<DetectedSignal> detectedSignals
) {
    public static SignalCheckResult success(List<DetectedSignal> detectedSignals) {
        return new SignalCheckResult(RiskCheckStatus.SUCCESS, null, detectedSignals);
    }

    public static SignalCheckResult undeterminable(RiskCheckReason reason) {
        return new SignalCheckResult(RiskCheckStatus.UNDETERMINABLE, reason, List.of());
    }

    public static SignalCheckResult failed(RiskCheckReason reason) {
        return new SignalCheckResult(RiskCheckStatus.FAILED, reason, List.of());
    }
}
