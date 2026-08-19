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

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

/**
 * 동일 사용자가 논리 삭제한 매물과 동일 주소·유사 가격/면적으로 짧은 기간 안에 재등록했는지 확인한다.
 * 주소는 정확히 일치해야 하고(도로명 우선, 없으면 지번), 가격·면적은 정책값으로 관리되는 허용
 * 오차(%) 이내면 "유사"로 본다.
 *
 * 원래 이 패턴("삭제 후 재등록")은 공개 매물 플랫폼에서 "오래 안 나가는 매물"을 신규처럼 보이게
 * 숨기는 어뷰징 수법인데, 이 서비스는 매물이 계정별 비공개(다른 사람은 내가 등록한 매물을 볼 수
 * 없음)라 그 동기 자체가 없다 - 실제로는 등록 시 오타 수정, 마음이 바뀌어 다시 고려하는 경우가
 * 대부분일 것으로 보인다. 그래서 "의심스럽다"고 단정하지 않고, 언제 삭제됐다가 다시 등록된 건지
 * 사실만 담아 판단은 사용자에게 맡긴다(DuplicateListingDetector와 동일한 원칙).
 */
@Component
@RequiredArgsConstructor
public class ShortTermRelistingDetector implements SignalDetector {

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

        Optional<Property> similarDeleted = recentlyDeleted.stream()
                .filter(deleted -> isSimilar(property, deleted))
                .findFirst();
        return similarDeleted
                .map(deleted -> SignalCheckResult.success(buildDescription(deleted)))
                .orElse(SignalCheckResult.success(null));
    }

    private String buildDescription(Property deleted) {
        long daysAgo = ChronoUnit.DAYS.between(deleted.getUpdatedAt().toLocalDate(), LocalDate.now());
        return "이 매물, %d일 전 삭제됐다가 비슷한 조건으로 다시 등록됐어요".formatted(daysAgo);
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
