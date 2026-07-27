package com.algogyeyak.riskanalysis.signal;

import com.algogyeyak.property.entity.Property;
import com.algogyeyak.riskanalysis.entity.RiskSignal;
import com.algogyeyak.riskanalysis.enums.RiskSignalType;

import java.util.List;

public interface SignalDetector {
    RiskSignalType type();
    boolean isEnabled(); // 정책 플래그
    List<RiskSignal> detect(Property property, MarketComparison marketComparison);
}
