package com.algogyeyak.riskanalysis.signal;

import com.algogyeyak.property.entity.Property;
import com.algogyeyak.property.entity.PropertyStatus;
import com.algogyeyak.property.repository.PropertyRepository;
import com.algogyeyak.riskanalysis.dto.MarketComparison;
import com.algogyeyak.riskanalysis.dto.SignalCheckResult;
import com.algogyeyak.riskanalysis.enums.RiskSignalType;
import com.algogyeyak.riskanalysis.policy.RiskPolicyConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
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
                .collect(Collectors.toSet());

        boolean isSuspicious = meetsCountThreshold && distinctRegions.size() >= MIN_DISTINCT_REGIONS;
        return isSuspicious ? SignalCheckResult.success(MULTIPLE_LISTING_MESSAGE) : SignalCheckResult.success(null);
    }

    // 지번주소 앞 두 토큰(예: "서울시 강남구")을 지역 구분 키로 쓴다.
    private static String regionOf(Property property) {
        String jibunAddress = property.getAddress().getJibunAddress();
        String[] tokens = jibunAddress.trim().split("\\s+");
        return tokens.length >= 2 ? tokens[0] + " " + tokens[1] : jibunAddress;
    }
}
