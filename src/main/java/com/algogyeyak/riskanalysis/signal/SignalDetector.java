package com.algogyeyak.riskanalysis.signal;

import com.algogyeyak.property.entity.Property;
import com.algogyeyak.riskanalysis.dto.MarketComparison;
import com.algogyeyak.riskanalysis.dto.SignalCheckResult;
import com.algogyeyak.riskanalysis.enums.RiskSignalType;

public interface SignalDetector {
    RiskSignalType type();
    boolean isEnabled();

    /**
     * 신호별로 독립적으로 판정한다. comparison은 시세비교가 필요한 탐지기(가격이상치 등)만 참고하고,
     * 나머지 탐지기는 comparison 상태와 무관하게 자체적으로 SUCCESS/UNDETERMINABLE/FAILED를 판단한다 —
     * 시세비교 실패가 다른 신호들의 판정까지 막지 않는다.
     */
    SignalCheckResult detect(Property property, MarketComparison comparison);
}
