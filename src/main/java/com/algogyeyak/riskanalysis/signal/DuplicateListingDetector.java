package com.algogyeyak.riskanalysis.signal;

import com.algogyeyak.property.entity.Property;
import com.algogyeyak.property.entity.PropertyAddress;
import com.algogyeyak.property.entity.PropertyStatus;
import com.algogyeyak.property.repository.PropertyRepository;
import com.algogyeyak.riskanalysis.dto.MarketComparison;
import com.algogyeyak.riskanalysis.dto.SignalCheckResult;
import com.algogyeyak.riskanalysis.enums.RiskCheckReason;
import com.algogyeyak.riskanalysis.enums.RiskSignalType;
import java.time.temporal.ChronoUnit;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 동일 주소·동일 거래유형으로 이미 등록된 다른 매물(다른 계정 포함)이 있는지 확인한다.
 * market-data(시세비교)와 무관하게 자체 DB 조회만으로 판정할 수 있어 comparison은 쓰지 않는다.
 *
 * 이 서비스는 임대인이 아니라 일반 사용자(세입자)가 검토용으로 매물을 등록하는 구조라, 같은 주소가
 * 중복 등록된 것 자체는 "여러 명이 같은 매물에 관심 있다"는 정상적인 상황일 수 있다(동일 유저의
 * 자기 자신 중복 등록은 PropertyService.register()가 이미 막고 있어, 이 신호가 걸리는 경우는 항상
 * 다른 계정이다). 그래서 "의심스럽다"고 시스템이 단정하지 않고, 비교 가능한 사실(가격·등록 시점)만
 * 담아 판단은 사용자에게 맡긴다 - 가격이 다르다고 곧바로 허위매물로 보기도 어렵다(안 나가서 가격을
 * 내렸을 수도 있음). 여러 건이 중복이면 가장 최근에 등록된 것과만 비교한다.
 */
@Component
@RequiredArgsConstructor
public class DuplicateListingDetector implements SignalDetector {

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

        List<Property> duplicates = address.getRoadAddress() != null
                ? propertyRepository.findAllByIdNotAndTransactionTypeAndStatusAndAddress_RoadAddressOrderByCreatedAtDesc(
                        property.getId(), property.getTransactionType(), PropertyStatus.ACTIVE, address.getRoadAddress())
                : propertyRepository.findAllByIdNotAndTransactionTypeAndStatusAndAddress_JibunAddressOrderByCreatedAtDesc(
                        property.getId(), property.getTransactionType(), PropertyStatus.ACTIVE, address.getJibunAddress());

        return duplicates.isEmpty() ? SignalCheckResult.success(null) : SignalCheckResult.success(buildDescription(duplicates.get(0)));
    }

    private String buildDescription(Property other) {
        long daysAgo = ChronoUnit.DAYS.between(other.getCreatedAt().toLocalDate(), java.time.LocalDate.now());
        String priceInfo = other.getMonthlyRent() != null
                ? "보증금 %,d원 / 월세 %,d원".formatted(other.getDeposit(), other.getMonthlyRent())
                : "보증금 %,d원".formatted(other.getDeposit());
        return "동일 주소로 등록된 다른 매물이 있어요 — %s, %d일 전 등록".formatted(priceInfo, daysAgo);
    }
}
