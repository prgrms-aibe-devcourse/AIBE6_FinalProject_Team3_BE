package com.algogyeyak.riskanalysis.signal;

import com.algogyeyak.property.entity.Property;
import com.algogyeyak.property.entity.PropertyAddress;
import com.algogyeyak.property.entity.PropertyStatus;
import com.algogyeyak.property.repository.PropertyRepository;
import com.algogyeyak.riskanalysis.dto.MarketComparison;
import com.algogyeyak.riskanalysis.dto.SignalCheckResult;
import com.algogyeyak.riskanalysis.enums.RiskCheckReason;
import com.algogyeyak.riskanalysis.enums.RiskSignalType;
import com.algogyeyak.riskanalysis.policy.RiskPolicyConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 동일 사용자가 논리 삭제한 매물과 동일 주소·유사 가격/면적으로 짧은 기간 안에 재등록했는지 확인한다.
 * 주소는 정확히 일치해야 하고(도로명 우선, 없으면 지번), 가격·면적은 정책값으로 관리되는 허용
 * 오차(%) 이내면 "유사"로 본다.
 */
@Component
@RequiredArgsConstructor
public class ShortTermRelistingDetector implements SignalDetector {

    private static final String RELISTED_MESSAGE = "이 매물, 최근에 삭제됐다가 비슷한 조건으로 다시 등록됐어요";

    private final PropertyRepository propertyRepository;
    private final RiskPolicyConfig policyConfig;

    @Override
    public RiskSignalType type() {
        return RiskSignalType.SHORT_TERM_RELISTING;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public SignalCheckResult detect(Property property, MarketComparison comparison) {
        PropertyAddress address = property.getAddress();
        if (address == null) {
            return SignalCheckResult.undeterminable(RiskCheckReason.ADDRESS_INFO_MISSING);
        }

        LocalDateTime windowStart = LocalDateTime.now().minusDays(policyConfig.getShortTermRelistingWindowDays());
        List<Property> recentlyDeleted = address.getRoadAddress() != null
                ? propertyRepository.findAllByUserIdAndStatusAndAddress_RoadAddressAndUpdatedAtAfter(
                        property.getUserId(), PropertyStatus.DELETED, address.getRoadAddress(), windowStart)
                : propertyRepository.findAllByUserIdAndStatusAndAddress_JibunAddressAndUpdatedAtAfter(
                        property.getUserId(), PropertyStatus.DELETED, address.getJibunAddress(), windowStart);

        boolean hasSimilarRelisting = recentlyDeleted.stream().anyMatch(deleted -> isSimilar(property, deleted));
        return hasSimilarRelisting ? SignalCheckResult.success(RELISTED_MESSAGE) : SignalCheckResult.success(null);
    }

    private boolean isSimilar(Property property, Property deleted) {
        return isWithinTolerance(property.getDeposit(), deleted.getDeposit(), policyConfig.getShortTermRelistingPriceTolerancePercent())
                && isWithinTolerance(property.getArea(), deleted.getArea(), policyConfig.getShortTermRelistingAreaTolerancePercent());
    }

    private boolean isWithinTolerance(Number actual, Number reference, int tolerancePercent) {
        double referenceValue = reference.doubleValue();
        double diff = Math.abs(actual.doubleValue() - referenceValue);
        return diff <= referenceValue * tolerancePercent / 100.0;
    }
}
