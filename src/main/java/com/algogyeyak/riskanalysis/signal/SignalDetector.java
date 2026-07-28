package com.algogyeyak.riskanalysis.signal;

import com.algogyeyak.property.entity.Property;
import com.algogyeyak.riskanalysis.dto.DetectedSignal;
import com.algogyeyak.riskanalysis.dto.MarketComparison;
import com.algogyeyak.riskanalysis.enums.RiskSignalType;

import java.util.List;

public interface SignalDetector {
    RiskSignalType type();
    boolean isEnabled();
    List<DetectedSignal> detect(Property property, MarketComparison comparison);
}
