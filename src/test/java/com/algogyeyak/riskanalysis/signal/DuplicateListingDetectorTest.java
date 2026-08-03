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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("DuplicateListingDetector")
class DuplicateListingDetectorTest {

    private final PropertyRepository propertyRepository = mock(PropertyRepository.class);
    private final DuplicateListingDetector detector = new DuplicateListingDetector(propertyRepository);

    private Property property(Long id, String roadAddress, String jibunAddress, TransactionType transactionType) {
        Property property = Property.builder()
                .userId(1L)
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
    @DisplayName("동일 도로명주소·동일 거래유형의 다른 활성 매물이 있으면 SUCCESS와 설명을 반환한다")
    void detectReturnsSuccessWithDescriptionWhenDuplicateExists() {
        Property property = property(10L, "서울시 강남구 테헤란로 1", "서울시 강남구 역삼동 1", TransactionType.JEONSE);
        when(propertyRepository.existsByIdNotAndTransactionTypeAndStatusAndAddress_RoadAddress(
                10L, TransactionType.JEONSE, PropertyStatus.ACTIVE, "서울시 강남구 테헤란로 1"))
                .thenReturn(true);

        SignalCheckResult result = detector.detect(property, null);

        assertThat(result.status()).isEqualTo(RiskCheckStatus.SUCCESS);
        assertThat(result.reason()).isNull();
        assertThat(result.description()).isEqualTo("동일 주소로 등록된 다른 매물이 있어요");
    }

    @Test
    @DisplayName("동일 주소의 다른 매물이 없으면 SUCCESS와 null 설명(리스크 없음)을 반환한다")
    void detectReturnsSuccessWithNullDescriptionWhenNoDuplicate() {
        Property property = property(10L, "서울시 강남구 테헤란로 1", "서울시 강남구 역삼동 1", TransactionType.JEONSE);
        when(propertyRepository.existsByIdNotAndTransactionTypeAndStatusAndAddress_RoadAddress(
                10L, TransactionType.JEONSE, PropertyStatus.ACTIVE, "서울시 강남구 테헤란로 1"))
                .thenReturn(false);

        SignalCheckResult result = detector.detect(property, null);

        assertThat(result.status()).isEqualTo(RiskCheckStatus.SUCCESS);
        assertThat(result.description()).isNull();
    }

    @Test
    @DisplayName("도로명주소가 없으면(단독·다가구) 지번주소 기준으로 중복을 확인한다")
    void detectFallsBackToJibunAddressWhenRoadAddressMissing() {
        Property property = property(10L, null, "서울시 강남구 역삼동 1", TransactionType.MONTHLY_RENT);
        when(propertyRepository.existsByIdNotAndTransactionTypeAndStatusAndAddress_JibunAddress(
                10L, TransactionType.MONTHLY_RENT, PropertyStatus.ACTIVE, "서울시 강남구 역삼동 1"))
                .thenReturn(true);

        SignalCheckResult result = detector.detect(property, null);

        assertThat(result.status()).isEqualTo(RiskCheckStatus.SUCCESS);
        assertThat(result.description()).isEqualTo("동일 주소로 등록된 다른 매물이 있어요");
    }

    @Test
    @DisplayName("매물에 주소 정보가 없으면 UNDETERMINABLE(ADDRESS_INFO_MISSING)을 반환한다")
    void detectReturnsUndeterminableWhenAddressMissing() {
        Property property = Property.builder()
                .userId(1L)
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
