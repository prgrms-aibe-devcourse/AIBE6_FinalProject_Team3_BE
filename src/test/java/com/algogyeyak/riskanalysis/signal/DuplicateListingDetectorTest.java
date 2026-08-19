package com.algogyeyak.riskanalysis.signal;

import com.algogyeyak.property.entity.Property;
import com.algogyeyak.property.entity.PropertyAddress;
import com.algogyeyak.property.entity.PropertyStatus;
import com.algogyeyak.property.entity.PropertyType;
import com.algogyeyak.property.entity.TransactionType;
import com.algogyeyak.property.repository.PropertyRepository;
import com.algogyeyak.riskanalysis.dto.SignalCheckResult;
import com.algogyeyak.riskanalysis.enums.RiskCheckReason;
import com.algogyeyak.riskanalysis.enums.RiskCheckStatus;
import com.algogyeyak.riskanalysis.enums.RiskSignalType;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("DuplicateListingDetector")
class DuplicateListingDetectorTest {

    private final PropertyRepository propertyRepository = mock(PropertyRepository.class);
    private final DuplicateListingDetector detector = new DuplicateListingDetector(propertyRepository);

    private Property property(Long id, String roadAddress, String jibunAddress, TransactionType transactionType) {
        Property property = Property.builder()
                .userId(1L)
                .title("테스트 매물")
                .propertyType(PropertyType.OFFICETEL)
                .transactionType(transactionType)
                .deposit(10_000_000L)
                .area(20.0)
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

    // 비교 대상(이미 등록된 다른 매물)용 - 가격/등록일 정보가 실제로 채워져 있어야 한다.
    private Property otherProperty(Long id, Long deposit, Long monthlyRent, LocalDateTime createdAt) {
        Property property = Property.builder()
                .userId(2L)
                .title("다른 사용자 매물")
                .propertyType(PropertyType.OFFICETEL)
                .transactionType(monthlyRent != null ? TransactionType.MONTHLY_RENT : TransactionType.JEONSE)
                .deposit(deposit)
                .monthlyRent(monthlyRent)
                .area(20.0)
                .build();
        ReflectionTestUtils.setField(property, "id", id);
        ReflectionTestUtils.setField(property, "createdAt", createdAt);
        return property;
    }

    @Test
    @DisplayName("type()은 DUPLICATE_LISTING을 반환한다")
    void typeReturnsDuplicateListing() {
        assertThat(detector.type()).isEqualTo(RiskSignalType.DUPLICATE_LISTING);
    }

    @Test
    @DisplayName("isEnabled()는 true를 반환한다")
    void isEnabledReturnsTrue() {
        assertThat(detector.isEnabled()).isTrue();
    }

    @Test
    @DisplayName("동일 도로명주소·동일 거래유형의 다른 활성 매물이 있으면 가격·등록일 비교 정보를 담은 설명을 반환한다")
    void detectReturnsSuccessWithComparisonDescriptionWhenDuplicateExists() {
        Property property = property(10L, "서울시 강남구 테헤란로 1", "서울시 강남구 역삼동 1", TransactionType.JEONSE);
        Property other = otherProperty(20L, 250_000_000L, null, LocalDateTime.now().minusDays(3));
        when(propertyRepository.findAllByIdNotAndTransactionTypeAndStatusAndAddress_RoadAddressOrderByCreatedAtDesc(
                10L, TransactionType.JEONSE, PropertyStatus.ACTIVE, "서울시 강남구 테헤란로 1"))
                .thenReturn(List.of(other));

        SignalCheckResult result = detector.detect(property, null);

        assertThat(result.status()).isEqualTo(RiskCheckStatus.SUCCESS);
        assertThat(result.reason()).isNull();
        // "의심된다"고 시스템이 단정하지 않고, 비교할 수 있는 사실(가격·등록 시점)만 담아 판단은
        // 사용자에게 맡긴다 - 이 도메인 전반의 "확정 판단이 아닌 참고용 정보" 원칙과 동일.
        assertThat(result.description()).isEqualTo("동일 주소로 등록된 다른 매물이 있어요 — 보증금 250,000,000원, 3일 전 등록");
    }

    @Test
    @DisplayName("월세 매물이 비교 대상이면 보증금과 월세를 함께 담는다")
    void detectIncludesMonthlyRentWhenComparisonIsMonthlyRent() {
        Property property = property(10L, "서울시 강남구 테헤란로 1", "서울시 강남구 역삼동 1", TransactionType.MONTHLY_RENT);
        Property other = otherProperty(20L, 10_000_000L, 800_000L, LocalDateTime.now().minusDays(1));
        when(propertyRepository.findAllByIdNotAndTransactionTypeAndStatusAndAddress_RoadAddressOrderByCreatedAtDesc(
                10L, TransactionType.MONTHLY_RENT, PropertyStatus.ACTIVE, "서울시 강남구 테헤란로 1"))
                .thenReturn(List.of(other));

        SignalCheckResult result = detector.detect(property, null);

        assertThat(result.description()).isEqualTo("동일 주소로 등록된 다른 매물이 있어요 — 보증금 10,000,000원 / 월세 800,000원, 1일 전 등록");
    }

    @Test
    @DisplayName("동일 주소의 다른 매물이 없으면 SUCCESS와 null 설명(리스크 없음)을 반환한다")
    void detectReturnsSuccessWithNullDescriptionWhenNoDuplicate() {
        Property property = property(10L, "서울시 강남구 테헤란로 1", "서울시 강남구 역삼동 1", TransactionType.JEONSE);
        when(propertyRepository.findAllByIdNotAndTransactionTypeAndStatusAndAddress_RoadAddressOrderByCreatedAtDesc(
                10L, TransactionType.JEONSE, PropertyStatus.ACTIVE, "서울시 강남구 테헤란로 1"))
                .thenReturn(List.of());

        SignalCheckResult result = detector.detect(property, null);

        assertThat(result.status()).isEqualTo(RiskCheckStatus.SUCCESS);
        assertThat(result.description()).isNull();
    }

    @Test
    @DisplayName("도로명주소가 없으면(단독·다가구) 지번주소 기준으로 중복을 확인한다")
    void detectFallsBackToJibunAddressWhenRoadAddressMissing() {
        Property property = property(10L, null, "서울시 강남구 역삼동 1", TransactionType.MONTHLY_RENT);
        Property other = otherProperty(20L, 10_000_000L, 800_000L, LocalDateTime.now().minusDays(1));
        when(propertyRepository.findAllByIdNotAndTransactionTypeAndStatusAndAddress_JibunAddressOrderByCreatedAtDesc(
                10L, TransactionType.MONTHLY_RENT, PropertyStatus.ACTIVE, "서울시 강남구 역삼동 1"))
                .thenReturn(List.of(other));

        SignalCheckResult result = detector.detect(property, null);

        assertThat(result.status()).isEqualTo(RiskCheckStatus.SUCCESS);
        assertThat(result.description()).isEqualTo("동일 주소로 등록된 다른 매물이 있어요 — 보증금 10,000,000원 / 월세 800,000원, 1일 전 등록");
    }

    @Test
    @DisplayName("매물에 주소 정보가 없으면 UNDETERMINABLE(ADDRESS_INFO_MISSING)을 반환한다")
    void detectReturnsUndeterminableWhenAddressMissing() {
        Property property = Property.builder()
                .userId(1L)
                .title("테스트 매물")
                .propertyType(PropertyType.OFFICETEL)
                .transactionType(TransactionType.JEONSE)
                .deposit(10_000_000L)
                .area(20.0)
                .build();
        ReflectionTestUtils.setField(property, "id", 10L);

        SignalCheckResult result = detector.detect(property, null);

        assertThat(result.status()).isEqualTo(RiskCheckStatus.UNDETERMINABLE);
        assertThat(result.reason()).isEqualTo(RiskCheckReason.ADDRESS_INFO_MISSING);
    }
}
