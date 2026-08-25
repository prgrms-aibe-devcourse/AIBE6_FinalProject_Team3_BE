package com.algogyeyak.riskanalysis.signal;

import com.algogyeyak.property.entity.Property;
import com.algogyeyak.property.entity.PropertyAddress;
import com.algogyeyak.property.entity.PropertyStatus;
import com.algogyeyak.property.repository.PropertyRepository;
import com.algogyeyak.riskanalysis.dto.MarketComparison;
import com.algogyeyak.riskanalysis.dto.SignalCheckResult;
import com.algogyeyak.riskanalysis.enums.RiskSignalType;
import com.algogyeyak.riskanalysis.policy.RiskPolicyConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 동일 계정이 짧은 기간 안에 서로 다른 지역의 매물을 여러 건 등록했는지 확인한다.
 * 요구사항 명세서에서 최종 탐지 기준(계정 수/기간/지역 다양성)이 팀 결정 대기(🔶 논의중)로 남아있어,
 * isEnabled()가 risk-policy의 multiAccountDetectionEnabled 플래그를 그대로 따르게 해 기본은 꺼둔다 -
 * 로직 자체는 미리 구현해두고, 팀 결정이 나면 플래그만 켜면 되는 구조.
 */
@Component
@RequiredArgsConstructor
public class SameAccountMultipleDetector implements SignalDetector {

    private static final String MULTIPLE_LISTING_MESSAGE = "동일 계정이 여러 매물을 동시에 등록했어요";
    private static final int MIN_DISTINCT_REGIONS = 2;

    private final PropertyRepository propertyRepository;
    private final RiskPolicyConfig policyConfig;

    @Override
    public RiskSignalType type() {
        return RiskSignalType.SAME_ACCOUNT_MULTIPLE;
    }

    @Override
    public boolean isEnabled() {
        return policyConfig.isMultiAccountDetectionEnabled();
    }

    @Override
    public SignalCheckResult detect(Property property, MarketComparison comparison) {
        LocalDateTime windowStart = LocalDateTime.now().minusDays(policyConfig.getSameAccountWindowDays());
        var recentProperties = propertyRepository.findAllByUserIdAndStatusAndCreatedAtAfter(
                property.getUserId(), PropertyStatus.ACTIVE, windowStart);

        boolean meetsCountThreshold = recentProperties.size() >= policyConfig.getSameAccountThresholdCount();
        Set<String> distinctRegions = recentProperties.stream()
                .map(SameAccountMultipleDetector::regionOf)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        boolean isSuspicious = meetsCountThreshold && distinctRegions.size() >= MIN_DISTINCT_REGIONS;
        return isSuspicious ? SignalCheckResult.success(MULTIPLE_LISTING_MESSAGE) : SignalCheckResult.success(null);
    }

    // 지번주소 앞 두 토큰(예: "서울시 강남구")을 지역 구분 키로 쓴다. 실제 register() 경로로 만들어진
    // 매물은 address가 항상 채워지지만(PropertyService 계약), 이 메서드는 그 계약에 기대지 않고
    // null을 방어한다 - address가 없는 매물이 하나라도 findAllByUserIdAndStatusAndCreatedAtAfter()
    // 결과에 섞이면(예: 테스트 픽스처, 향후 다른 경로로 생성된 매물) 이 스트림 전체가 NPE로 끊겨
    // checkAndSave(Property)의 detectors 루프가 이 신호 이후로는 전혀 실행되지 않게 된다 - 신호 하나가
    // 판정불가여야 할 상황이 신호 4종 전체를 조용히 망가뜨리는 건 과한 대가라 null은 그냥 지역
    // 구분에서 제외한다(2026-08-24, multi-account-detection-enabled를 true로 켠 뒤
    // FakeListingSignalServiceConcurrencyTest에서 실측 발견).
    private static String regionOf(Property property) {
        PropertyAddress address = property.getAddress();
        if (address == null || address.getJibunAddress() == null) {
            return null;
        }
        String jibunAddress = address.getJibunAddress();
        String[] tokens = jibunAddress.trim().split("\\s+");
        return tokens.length >= 2 ? tokens[0] + " " + tokens[1] : jibunAddress;
    }
}
