package com.algogyeyak.riskanalysis.signal;

import com.algogyeyak.property.entity.Property;
import com.algogyeyak.property.entity.PropertyAddress;
import com.algogyeyak.property.entity.PropertyStatus;
import com.algogyeyak.property.entity.PropertyType;
import com.algogyeyak.property.entity.TransactionType;
import com.algogyeyak.property.repository.PropertyRepository;
import com.algogyeyak.riskanalysis.dto.SignalCheckResult;
import com.algogyeyak.riskanalysis.enums.RiskCheckStatus;
import com.algogyeyak.riskanalysis.enums.RiskSignalType;
import com.algogyeyak.riskanalysis.policy.RiskPolicyConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("SameAccountMultipleDetector")
class SameAccountMultipleDetectorTest {

    private final PropertyRepository propertyRepository = mock(PropertyRepository.class);
    private final RiskPolicyConfig policyConfig = new RiskPolicyConfig();
    private final SameAccountMultipleDetector detector = new SameAccountMultipleDetector(propertyRepository, policyConfig);

    private Property property(String jibunAddress) {
        Property property = Property.builder()
                .userId(1L)
                .propertyType(PropertyType.OFFICETEL)
                .transactionType(TransactionType.JEONSE)
                .deposit(10_000_000L)
                .area(20.0)
                .build();
        PropertyAddress address = PropertyAddress.builder()
                .jibunAddress(jibunAddress)
                .latitude(37.5)
                .longitude(127.0)
                .build();
        property.assignAddress(address);
        return property;
    }

    @Test
    @DisplayName("type()은 SAME_ACCOUNT_MULTIPLE을 반환한다")
    void typeReturnsSameAccountMultiple() {
        assertThat(detector.type()).isEqualTo(RiskSignalType.SAME_ACCOUNT_MULTIPLE);
    }

    @Test
    @DisplayName("isEnabled()는 risk-policy의 multiAccountDetectionEnabled 값을 그대로 따른다")
    void isEnabledFollowsPolicyFlag() {
        policyConfig.setMultiAccountDetectionEnabled(false);
        assertThat(detector.isEnabled()).isFalse();

        policyConfig.setMultiAccountDetectionEnabled(true);
        assertThat(detector.isEnabled()).isTrue();
    }

    @Test
    @DisplayName("임계값 이상 매물을 서로 다른 지역에 최근 등록했으면 SUCCESS와 설명을 반환한다")
    void detectReturnsSuccessWithDescriptionWhenThresholdExceededAcrossRegions() {
        ReflectionTestUtils.setField(policyConfig, "sameAccountThresholdCount", 3);
        ReflectionTestUtils.setField(policyConfig, "sameAccountWindowDays", 7);
        Property target = property("서울시 강남구 역삼동 1");
        List<Property> recentProperties = List.of(
                property("서울시 강남구 역삼동 1"),
                property("서울시 마포구 합정동 2"),
                property("서울시 송파구 잠실동 3")
        );
        when(propertyRepository.findAllByUserIdAndStatusAndCreatedAtAfter(any(), any(), any()))
                .thenReturn(recentProperties);

        SignalCheckResult result = detector.detect(target, null);

        assertThat(result.status()).isEqualTo(RiskCheckStatus.SUCCESS);
        assertThat(result.description()).isEqualTo("동일 계정이 여러 매물을 동시에 등록했어요");
    }

    @Test
    @DisplayName("매물 수가 임계값 미만이면 SUCCESS와 null 설명(리스크 없음)을 반환한다")
    void detectReturnsSuccessWithNullDescriptionWhenBelowThreshold() {
        ReflectionTestUtils.setField(policyConfig, "sameAccountThresholdCount", 3);
        ReflectionTestUtils.setField(policyConfig, "sameAccountWindowDays", 7);
        Property target = property("서울시 강남구 역삼동 1");
        List<Property> recentProperties = List.of(
                property("서울시 강남구 역삼동 1"),
                property("서울시 마포구 합정동 2")
        );
        when(propertyRepository.findAllByUserIdAndStatusAndCreatedAtAfter(any(), any(), any()))
                .thenReturn(recentProperties);

        SignalCheckResult result = detector.detect(target, null);

        assertThat(result.status()).isEqualTo(RiskCheckStatus.SUCCESS);
        assertThat(result.description()).isNull();
    }

    @Test
    @DisplayName("매물 수는 임계값 이상이어도 전부 같은 지역이면 SUCCESS와 null 설명(리스크 없음)을 반환한다")
    void detectReturnsSuccessWithNullDescriptionWhenAllSameRegion() {
        ReflectionTestUtils.setField(policyConfig, "sameAccountThresholdCount", 3);
        ReflectionTestUtils.setField(policyConfig, "sameAccountWindowDays", 7);
        Property target = property("서울시 강남구 역삼동 1");
        List<Property> recentProperties = List.of(
                property("서울시 강남구 역삼동 1"),
                property("서울시 강남구 역삼동 2"),
                property("서울시 강남구 역삼동 3")
        );
        when(propertyRepository.findAllByUserIdAndStatusAndCreatedAtAfter(any(), any(), any()))
                .thenReturn(recentProperties);

        SignalCheckResult result = detector.detect(target, null);

        assertThat(result.status()).isEqualTo(RiskCheckStatus.SUCCESS);
        assertThat(result.description()).isNull();
    }
}
