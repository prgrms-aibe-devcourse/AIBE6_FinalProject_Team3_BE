package com.algogyeyak.riskanalysis.signal;

import com.algogyeyak.property.entity.Property;
import com.algogyeyak.riskanalysis.dto.MarketComparison;
import com.algogyeyak.riskanalysis.dto.SignalCheckResult;
import com.algogyeyak.riskanalysis.enums.MarketComparisonStatus;
import com.algogyeyak.riskanalysis.enums.MarketUnavailableReason;
import com.algogyeyak.riskanalysis.enums.RiskCheckReason;
import com.algogyeyak.riskanalysis.enums.RiskSignalType;
import com.algogyeyak.riskanalysis.policy.RiskPolicyConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 매물 가격과 대표 실거래가(market-data 시세비교)를 비교해, 시세 대비
 * RiskPolicyConfig.priceAnomalyPercent% 이상 저렴하면 신호로 기록한다.
 * 4개 탐지기 중 유일하게 comparison(MarketComparison)을 실제로 참고한다.
 */
@Component
@RequiredArgsConstructor
public class PriceAnomalyDetector implements SignalDetector {

    private final RiskPolicyConfig policyConfig;

    @Override
    public RiskSignalType type() {
        return RiskSignalType.PRICE_ANOMALY;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public SignalCheckResult detect(Property property, MarketComparison comparison) {
        if (comparison == null) {
            return SignalCheckResult.undeterminable(RiskCheckReason.NO_COMPARABLE_TRANSACTION);
        }
        if (comparison.status() == MarketComparisonStatus.FAILED) {
            return SignalCheckResult.failed(mapReason(comparison.reason()));
        }
        if (comparison.status() == MarketComparisonStatus.UNDETERMINABLE) {
            return SignalCheckResult.undeterminable(mapReason(comparison.reason()));
        }

        BigDecimal thresholdRate = BigDecimal.valueOf(-policyConfig.getPriceAnomalyPercent())
                .divide(BigDecimal.valueOf(100));
        boolean isAnomaly = comparison.differenceRate().compareTo(thresholdRate) <= 0;
        if (!isAnomaly) {
            return SignalCheckResult.success(null);
        }

        int actualPercent = comparison.differenceRate().abs()
                .multiply(BigDecimal.valueOf(100))
                .setScale(0, RoundingMode.HALF_UP)
                .intValue();
        return SignalCheckResult.success("시세보다 " + actualPercent + "% 낮은 가격이에요");
    }

    private RiskCheckReason mapReason(MarketUnavailableReason reason) {
        if (reason == null) {
            return RiskCheckReason.NO_COMPARABLE_TRANSACTION;
        }
        return switch (reason) {
            case INSUFFICIENT_SAMPLE -> RiskCheckReason.NO_COMPARABLE_TRANSACTION;
            case ADDRESS_INFO_MISSING -> RiskCheckReason.ADDRESS_INFO_MISSING;
            case PROPERTY_TYPE_UNSUPPORTED -> RiskCheckReason.PROPERTY_TYPE_UNSUPPORTED;
            case TRANSACTION_TYPE_UNSUPPORTED -> RiskCheckReason.TRANSACTION_TYPE_UNSUPPORTED;
            case EXTERNAL_API_FAILURE, RATE_LIMIT_EXCEEDED, INVALID_RESPONSE_FORMAT -> RiskCheckReason.DATA_FETCH_FAILURE;
        };
    }
}
