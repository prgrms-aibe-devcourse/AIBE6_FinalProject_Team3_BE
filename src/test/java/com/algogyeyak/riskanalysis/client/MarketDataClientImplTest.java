package com.algogyeyak.riskanalysis.client;

import com.algogyeyak.marketdata.dto.MarketComparisonResponse;
import com.algogyeyak.marketdata.dto.MarketComparisonUnavailableReason;
import com.algogyeyak.marketdata.service.MarketComparisonService;
import com.algogyeyak.property.entity.Property;
import com.algogyeyak.property.entity.PropertyType;
import com.algogyeyak.property.entity.TransactionType;
import com.algogyeyak.property.repository.PropertyRepository;
import com.algogyeyak.riskanalysis.dto.MarketComparison;
import com.algogyeyak.riskanalysis.enums.MarketComparisonStatus;
import com.algogyeyak.riskanalysis.enums.MarketUnavailableReason;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("MarketDataClientImpl")
class MarketDataClientImplTest {

    private final PropertyRepository propertyRepository = mock(PropertyRepository.class);
    private final MarketComparisonService marketComparisonService = mock(MarketComparisonService.class);
    private final MarketDataClientImpl client = new MarketDataClientImpl(propertyRepository, marketComparisonService);

    private Property property(Long id, long deposit) {
        Property property = Property.builder()
                .userId(1L)
                .propertyType(PropertyType.OFFICETEL)
                .transactionType(TransactionType.JEONSE)
                .deposit(deposit)
                .area(20.0)
                .build();
        ReflectionTestUtils.setField(property, "id", id);
        return property;
    }

    @Test
    @DisplayName("존재하지 않는 매물이면 빈 Optional을 반환한다")
    void getComparisonReturnsEmptyWhenPropertyNotFound() {
        when(propertyRepository.findById(10L)).thenReturn(Optional.empty());

        Optional<MarketComparison> result = client.getComparison(10L);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("AVAILABLE이면 SUCCESS 상태로 변환하고 매물 보증금을 askingPrice로 채운다")
    void getComparisonMapsAvailableToSuccess() {
        Property property = property(10L, 200_000_000L);
        when(propertyRepository.findById(10L)).thenReturn(Optional.of(property));
        when(marketComparisonService.compare(property)).thenReturn(
                MarketComparisonResponse.available(190_000_000L, -0.05, 3, "2026-06-15", 300)
        );

        MarketComparison result = client.getComparison(10L).orElseThrow();

        assertThat(result.status()).isEqualTo(MarketComparisonStatus.SUCCESS);
        assertThat(result.reason()).isNull();
        assertThat(result.referencePrice()).isEqualByComparingTo(BigDecimal.valueOf(190_000_000L));
        assertThat(result.askingPrice()).isEqualByComparingTo(BigDecimal.valueOf(200_000_000L));
        assertThat(result.differenceRate()).isEqualByComparingTo(BigDecimal.valueOf(-0.05));
        assertThat(result.sampleCount()).isEqualTo(3);
        assertThat(result.referenceDate()).isEqualTo(java.time.LocalDate.of(2026, 6, 15));
        assertThat(result.appliedRadius()).isEqualTo(300);
    }

    @Test
    @DisplayName("UNAVAILABLE이면 UNDETERMINABLE 상태로 변환하고 사유를 매핑한다")
    void getComparisonMapsUnavailableToUndeterminable() {
        Property property = property(10L, 200_000_000L);
        when(propertyRepository.findById(10L)).thenReturn(Optional.of(property));
        when(marketComparisonService.compare(property)).thenReturn(
                MarketComparisonResponse.unavailable(MarketComparisonUnavailableReason.INSUFFICIENT_SAMPLE, "표본 부족")
        );

        MarketComparison result = client.getComparison(10L).orElseThrow();

        assertThat(result.status()).isEqualTo(MarketComparisonStatus.UNDETERMINABLE);
        assertThat(result.reason()).isEqualTo(MarketUnavailableReason.INSUFFICIENT_SAMPLE);
        assertThat(result.referencePrice()).isNull();
    }

    @Test
    @DisplayName("거래유형·매물유형 미지원 사유는 PROPERTY_TYPE_UNSUPPORTED로 매핑한다")
    void getComparisonMapsUnsupportedTypeReasons() {
        Property property = property(10L, 200_000_000L);
        when(propertyRepository.findById(10L)).thenReturn(Optional.of(property));
        when(marketComparisonService.compare(property)).thenReturn(
                MarketComparisonResponse.unavailable(MarketComparisonUnavailableReason.TRANSACTION_TYPE_UNSUPPORTED, "월세")
        );

        MarketComparison result = client.getComparison(10L).orElseThrow();

        assertThat(result.reason()).isEqualTo(MarketUnavailableReason.PROPERTY_TYPE_UNSUPPORTED);
    }
}
