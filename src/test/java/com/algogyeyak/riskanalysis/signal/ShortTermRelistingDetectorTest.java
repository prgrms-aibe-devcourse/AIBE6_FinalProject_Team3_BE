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

@DisplayName("ShortTermRelistingDetector")
class ShortTermRelistingDetectorTest {

    private final PropertyRepository propertyRepository = mock(PropertyRepository.class);
    private final RiskPolicyConfig policyConfig = new RiskPolicyConfig();
    private final ShortTermRelistingDetector detector = new ShortTermRelistingDetector(propertyRepository, policyConfig);

    private Property property(Long id, String roadAddress, String jibunAddress, Long deposit, Double area) {
        Property property = Property.builder()
                .userId(1L)
                .title("테스트 매물")
                .propertyType(PropertyType.OFFICETEL)
                .transactionType(TransactionType.JEONSE)
                .deposit(deposit)
                .area(area)
                .build();
        ReflectionTestUtils.setField(property, "id", id);
        PropertyAddress address = PropertyAddress.builder()
                .roadAddress(roadAddress)
                .jibunAddress(jibunAddress)
                .latitude(37.5)
                .longitude(127.0)
                .build();
        property.assignAddress(address);
        return property;
    }

    private void setPolicy(int windowDays, int priceTolerancePercent, int areaTolerancePercent) {
        ReflectionTestUtils.setField(policyConfig, "shortTermRelistingWindowDays", windowDays);
        ReflectionTestUtils.setField(policyConfig, "shortTermRelistingPriceTolerancePercent", priceTolerancePercent);
        ReflectionTestUtils.setField(policyConfig, "shortTermRelistingAreaTolerancePercent", areaTolerancePercent);
    }

    @Test
    @DisplayName("type()은 SHORT_TERM_RELISTING을 반환한다")
    void typeReturnsShortTermRelisting() {
        assertThat(detector.type()).isEqualTo(RiskSignalType.SHORT_TERM_RELISTING);
    }

    @Test
    @DisplayName("isEnabled()는 true를 반환한다")
    void isEnabledReturnsTrue() {
        assertThat(detector.isEnabled()).isTrue();
    }

    @Test
    @DisplayName("동일 주소·유사 가격/면적으로 삭제된 매물이 최근에 있으면 SUCCESS와 설명을 반환한다")
    void detectReturnsSuccessWithDescriptionWhenSimilarDeletedPropertyExistsRecently() {
        setPolicy(30, 5, 10);
        Property target = property(10L, "서울시 강남구 테헤란로 1", "서울시 강남구 역삼동 1", 100_000_000L, 20.0);
        Property deletedSimilar = property(9L, "서울시 강남구 테헤란로 1", "서울시 강남구 역삼동 1", 102_000_000L, 21.0);
        when(propertyRepository.findAllByUserIdAndStatusAndAddress_RoadAddressAndUpdatedAtAfter(
                any(), any(), any(), any()))
                .thenReturn(List.of(deletedSimilar));

        SignalCheckResult result = detector.detect(target, null);

        assertThat(result.status()).isEqualTo(RiskCheckStatus.SUCCESS);
        assertThat(result.description()).isEqualTo("이 매물, 최근에 삭제됐다가 비슷한 조건으로 다시 등록됐어요");
    }

    @Test
    @DisplayName("최근 삭제된 동일 주소 매물이 없으면 SUCCESS와 null 설명(리스크 없음)을 반환한다")
    void detectReturnsSuccessWithNullDescriptionWhenNoRecentDeletedProperty() {
        setPolicy(30, 5, 10);
        Property target = property(10L, "서울시 강남구 테헤란로 1", "서울시 강남구 역삼동 1", 100_000_000L, 20.0);
        when(propertyRepository.findAllByUserIdAndStatusAndAddress_RoadAddressAndUpdatedAtAfter(
                any(), any(), any(), any()))
                .thenReturn(List.of());

        SignalCheckResult result = detector.detect(target, null);

        assertThat(result.status()).isEqualTo(RiskCheckStatus.SUCCESS);
        assertThat(result.description()).isNull();
    }

    @Test
    @DisplayName("동일 주소로 삭제된 매물이 있어도 가격 차이가 허용 오차를 넘으면 SUCCESS와 null 설명을 반환한다")
    void detectReturnsSuccessWithNullDescriptionWhenPriceOutOfTolerance() {
        setPolicy(30, 5, 10);
        Property target = property(10L, "서울시 강남구 테헤란로 1", "서울시 강남구 역삼동 1", 100_000_000L, 20.0);
        Property deletedDifferentPrice = property(9L, "서울시 강남구 테헤란로 1", "서울시 강남구 역삼동 1", 150_000_000L, 20.0);
        when(propertyRepository.findAllByUserIdAndStatusAndAddress_RoadAddressAndUpdatedAtAfter(
                any(), any(), any(), any()))
                .thenReturn(List.of(deletedDifferentPrice));

        SignalCheckResult result = detector.detect(target, null);

        assertThat(result.status()).isEqualTo(RiskCheckStatus.SUCCESS);
        assertThat(result.description()).isNull();
    }

    @Test
    @DisplayName("동일 주소로 삭제된 매물이 있어도 면적 차이가 허용 오차를 넘으면 SUCCESS와 null 설명을 반환한다")
    void detectReturnsSuccessWithNullDescriptionWhenAreaOutOfTolerance() {
        setPolicy(30, 5, 10);
        Property target = property(10L, "서울시 강남구 테헤란로 1", "서울시 강남구 역삼동 1", 100_000_000L, 20.0);
        Property deletedDifferentArea = property(9L, "서울시 강남구 테헤란로 1", "서울시 강남구 역삼동 1", 100_000_000L, 40.0);
        when(propertyRepository.findAllByUserIdAndStatusAndAddress_RoadAddressAndUpdatedAtAfter(
                any(), any(), any(), any()))
                .thenReturn(List.of(deletedDifferentArea));

        SignalCheckResult result = detector.detect(target, null);

        assertThat(result.status()).isEqualTo(RiskCheckStatus.SUCCESS);
        assertThat(result.description()).isNull();
    }

    @Test
    @DisplayName("도로명주소가 없으면(단독·다가구) 지번주소 기준으로 재등록 여부를 확인한다")
    void detectFallsBackToJibunAddressWhenRoadAddressMissing() {
        setPolicy(30, 5, 10);
        Property target = property(10L, null, "서울시 강남구 역삼동 1", 100_000_000L, 20.0);
        Property deletedSimilar = property(9L, null, "서울시 강남구 역삼동 1", 101_000_000L, 20.5);
        when(propertyRepository.findAllByUserIdAndStatusAndAddress_JibunAddressAndUpdatedAtAfter(
                any(), any(), any(), any()))
                .thenReturn(List.of(deletedSimilar));

        SignalCheckResult result = detector.detect(target, null);

        assertThat(result.status()).isEqualTo(RiskCheckStatus.SUCCESS);
        assertThat(result.description()).isEqualTo("이 매물, 최근에 삭제됐다가 비슷한 조건으로 다시 등록됐어요");
    }
}
