package com.algogyeyak.riskanalysis.signal;

import com.algogyeyak.property.entity.Property;
import com.algogyeyak.property.entity.PropertyAddress;
import com.algogyeyak.property.entity.PropertyStatus;
import com.algogyeyak.property.repository.PropertyRepository;
import com.algogyeyak.riskanalysis.dto.MarketComparison;
import com.algogyeyak.riskanalysis.dto.SignalCheckResult;
import com.algogyeyak.riskanalysis.enums.RiskCheckReason;
import com.algogyeyak.riskanalysis.enums.RiskSignalType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 동일 주소·동일 거래유형으로 이미 등록된 다른 매물(다른 계정 포함)이 있는지 확인한다.
 * market-data(시세비교)와 무관하게 자체 DB 조회만으로 판정할 수 있어 comparison은 쓰지 않는다.
 */
@Component
@RequiredArgsConstructor
public class DuplicateListingDetector implements SignalDetector {

    private static final String DUPLICATE_FOUND_MESSAGE = "동일 주소로 등록된 다른 매물이 있어요";

    private final PropertyRepository propertyRepository;

    @Override
    public RiskSignalType type() {
        return RiskSignalType.DUPLICATE_LISTING;
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

        boolean duplicateExists = address.getRoadAddress() != null
                ? propertyRepository.existsByIdNotAndTransactionTypeAndStatusAndAddress_RoadAddress(
                        property.getId(), property.getTransactionType(), PropertyStatus.ACTIVE, address.getRoadAddress())
                : propertyRepository.existsByIdNotAndTransactionTypeAndStatusAndAddress_JibunAddress(
                        property.getId(), property.getTransactionType(), PropertyStatus.ACTIVE, address.getJibunAddress());

        return duplicateExists ? SignalCheckResult.success(DUPLICATE_FOUND_MESSAGE) : SignalCheckResult.success(null);
    }
}
