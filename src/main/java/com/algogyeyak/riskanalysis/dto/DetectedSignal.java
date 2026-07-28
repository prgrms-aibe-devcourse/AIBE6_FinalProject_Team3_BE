package com.algogyeyak.riskanalysis.dto;

import com.algogyeyak.riskanalysis.enums.RiskSignalType;

public record DetectedSignal(
        RiskSignalType signalType,
        String description
) {
    public static DetectedSignal of(RiskSignalType type, String description) {
        return new DetectedSignal(type, description);
    }
}
