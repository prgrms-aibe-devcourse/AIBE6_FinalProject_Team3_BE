package com.algogyeyak.marketdata.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.algogyeyak.marketdata.client.MolitTradeClient;
import com.algogyeyak.marketdata.client.TradeTransactionSample;
import com.algogyeyak.marketdata.config.MarketComparisonProperties;
import com.algogyeyak.marketdata.dto.MarketComparisonUnavailableReason;
import com.algogyeyak.marketdata.dto.MarketSaleComparisonResponse;
import com.algogyeyak.property.client.AddressResolutionResult;
import com.algogyeyak.property.client.KakaoAddressClient;
import com.algogyeyak.property.client.KakaoRegionCodeClient;
import com.algogyeyak.property.client.RegionCodeResult;
import com.algogyeyak.property.entity.Property;
import com.algogyeyak.property.entity.PropertyAddress;
import com.algogyeyak.property.entity.PropertyType;
import com.algogyeyak.property.entity.TransactionType;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MarketSaleComparisonServiceTest {

    @Mock
    private KakaoRegionCodeClient kakaoRegionCodeClient;

    @Mock
    private KakaoAddressClient kakaoAddressClient;

    @Mock
    private MolitTradeClient molitTradeClient;

    private MarketSaleComparisonService service;

    private static final double PROPERTY_LAT = 37.5665;
    private static final double PROPERTY_LNG = 126.9780;
    private static final String LAWD_CD = "11110";

    private static final MarketComparisonProperties PROPERTIES =
            new MarketComparisonProperties(300, 600, 3, 0.2, 6, 30);

    private void init() {
        service = new MarketSaleComparisonService(kakaoRegionCodeClient, kakaoAddressClient, molitTradeClient, PROPERTIES);
    }

    private Property officetel(double area) {
        Property property = Property.builder()
                .userId(1L)
                .title("테스트 매물")
                .propertyType(PropertyType.OFFICETEL)
                .transactionType(TransactionType.JEONSE)
                .deposit(200_000_000L)
                .area(area)
                .description("테스트 매물")
                .build();
        property.assignAddress(PropertyAddress.builder()
                .roadAddress("서울특별시 종로구 세종대로 1")
                .jibunAddress("서울특별시 종로구 세종로 1")
                .latitude(PROPERTY_LAT)
                .longitude(PROPERTY_LNG)
                .build());
        return property;
    }

    private RegionCodeResult resolvedRegion() {
        return RegionCodeResult.builder()
                .resolved(true)
                .legalDongCode("1111010100")
                .lawdCd(LAWD_CD)
                .regionName("서울특별시 종로구 청운동")
                .build();
    }

    private TradeTransactionSample sample(String jibun, long dealAmountWon, double area) {
        return TradeTransactionSample.builder()
                .propertyType(PropertyType.OFFICETEL)
                .buildingName("테스트오피스텔")
                .jibunAddress(jibun)
                .legalDongCode(LAWD_CD)
                .legalDongName("청운동")
                .dealDate(LocalDate.of(2026, 6, 15))
                .dealAmountWon(dealAmountWon)
                .areaSqm(area)
                .build();
    }

    private AddressResolutionResult resolvedAt(double lat, double lng) {
        return AddressResolutionResult.builder()
                .resolved(true)
                .jibunAddress("geocoded")
                .latitude(lat)
                .longitude(lng)
                .build();
    }

    private void stubFirstMonthOnly(List<TradeTransactionSample> samples) {
        when(molitTradeClient.fetch(eq(PropertyType.OFFICETEL), eq(LAWD_CD), any(YearMonth.class)))
                .thenReturn(samples, List.of(), List.of(), List.of(), List.of(), List.of());
    }

    @Test
    void 좌표가_없으면_판정불가를_반환한다() {
        init();
        Property property = Property.builder()
                .userId(1L)
                .title("테스트 매물")
                .propertyType(PropertyType.OFFICETEL)
                .transactionType(TransactionType.JEONSE)
                .deposit(200_000_000L)
                .area(25.0)
                .build();
        // 주소를 아예 할당하지 않음 -> address == null

        MarketSaleComparisonResponse response = service.compare(property);

        assertThat(response.status()).isEqualTo("UNAVAILABLE");
        assertThat(response.reason()).isEqualTo(MarketComparisonUnavailableReason.ADDRESS_INFO_MISSING);
        verifyNoInteractions(kakaoRegionCodeClient, molitTradeClient);
    }

    @Test
    void 법정동코드_조회에_실패하면_판정불가를_반환한다() {
        init();
        Property property = officetel(25.0);
        when(kakaoRegionCodeClient.resolve(PROPERTY_LAT, PROPERTY_LNG)).thenReturn(RegionCodeResult.unresolved());

        MarketSaleComparisonResponse response = service.compare(property);

        assertThat(response.status()).isEqualTo("UNAVAILABLE");
        assertThat(response.reason()).isEqualTo(MarketComparisonUnavailableReason.ADDRESS_INFO_MISSING);
        verifyNoInteractions(molitTradeClient);
    }

    @Test
    void 반경300m_이내_표본이_3건이면_중앙값으로_AVAILABLE을_반환한다() {
        init();
        Property property = officetel(25.0);
        when(kakaoRegionCodeClient.resolve(PROPERTY_LAT, PROPERTY_LNG)).thenReturn(resolvedRegion());

        List<TradeTransactionSample> samples = List.of(
                sample("1-1", 900_000_000L, 24.0),
                sample("1-2", 1_000_000_000L, 25.0),
                sample("1-3", 1_100_000_000L, 26.0)
        );
        stubFirstMonthOnly(samples);
        when(kakaoAddressClient.resolve(anyString())).thenReturn(resolvedAt(PROPERTY_LAT, PROPERTY_LNG));

        MarketSaleComparisonResponse response = service.compare(property);

        assertThat(response.status()).isEqualTo("AVAILABLE");
        assertThat(response.sampleCount()).isEqualTo(3);
        assertThat(response.referencePrice()).isEqualTo(1_000_000_000L);
        assertThat(response.radiusMeters()).isEqualTo(300);
        assertThat(response.reason()).isNull();
    }

    @Test
    void _300m_이내가_부족해_600m로_확장하는_경우를_검증한다() {
        init();
        Property property = officetel(25.0);
        when(kakaoRegionCodeClient.resolve(PROPERTY_LAT, PROPERTY_LNG)).thenReturn(resolvedRegion());

        TradeTransactionSample near = sample("near-1", 950_000_000L, 25.0);
        TradeTransactionSample mid1 = sample("mid-1", 1_000_000_000L, 25.0);
        TradeTransactionSample mid2 = sample("mid-2", 1_050_000_000L, 25.0);
        stubFirstMonthOnly(List.of(near, mid1, mid2));

        double midLat = PROPERTY_LAT + 0.0035; // 약 389m: 300m 밖, 600m 이내
        String prefix = "서울특별시 종로구 청운동 ";
        when(kakaoAddressClient.resolve(prefix + "near-1")).thenReturn(resolvedAt(PROPERTY_LAT, PROPERTY_LNG));
        when(kakaoAddressClient.resolve(prefix + "mid-1")).thenReturn(resolvedAt(midLat, PROPERTY_LNG));
        when(kakaoAddressClient.resolve(prefix + "mid-2")).thenReturn(resolvedAt(midLat, PROPERTY_LNG));

        MarketSaleComparisonResponse response = service.compare(property);

        assertThat(response.status()).isEqualTo("AVAILABLE");
        assertThat(response.sampleCount()).isEqualTo(3);
        assertThat(response.referencePrice()).isEqualTo(1_000_000_000L);
        assertThat(response.radiusMeters()).isEqualTo(600);
    }

    @Test
    void _600m_이내에서도_표본이_3건_미만이면_판정불가를_반환한다() {
        init();
        Property property = officetel(25.0);
        when(kakaoRegionCodeClient.resolve(PROPERTY_LAT, PROPERTY_LNG)).thenReturn(resolvedRegion());

        TradeTransactionSample near = sample("near-1", 950_000_000L, 25.0);
        TradeTransactionSample mid = sample("mid-1", 1_000_000_000L, 25.0);
        stubFirstMonthOnly(List.of(near, mid));

        String prefix = "서울특별시 종로구 청운동 ";
        when(kakaoAddressClient.resolve(prefix + "near-1")).thenReturn(resolvedAt(PROPERTY_LAT, PROPERTY_LNG));
        when(kakaoAddressClient.resolve(prefix + "mid-1"))
                .thenReturn(resolvedAt(PROPERTY_LAT + 0.0035, PROPERTY_LNG));

        MarketSaleComparisonResponse response = service.compare(property);

        assertThat(response.status()).isEqualTo("UNAVAILABLE");
        assertThat(response.message()).contains("부족");
        assertThat(response.reason()).isEqualTo(MarketComparisonUnavailableReason.INSUFFICIENT_SAMPLE);
    }

    @Test
    void 면적오차가_20퍼센트를_넘는_표본은_후보에서_제외된다() {
        init();
        Property property = officetel(25.0);
        when(kakaoRegionCodeClient.resolve(PROPERTY_LAT, PROPERTY_LNG)).thenReturn(resolvedRegion());

        TradeTransactionSample within1 = sample("a-1", 950_000_000L, 24.0);
        TradeTransactionSample within2 = sample("a-2", 1_000_000_000L, 26.0);
        TradeTransactionSample outOfRange = sample("a-3", 4_000_000_000L, 40.0);
        stubFirstMonthOnly(List.of(within1, within2, outOfRange));

        String prefix = "서울특별시 종로구 청운동 ";
        when(kakaoAddressClient.resolve(prefix + "a-1")).thenReturn(resolvedAt(PROPERTY_LAT, PROPERTY_LNG));
        when(kakaoAddressClient.resolve(prefix + "a-2")).thenReturn(resolvedAt(PROPERTY_LAT, PROPERTY_LNG));

        MarketSaleComparisonResponse response = service.compare(property);

        assertThat(response.status()).isEqualTo("UNAVAILABLE");
        verify(kakaoAddressClient, never()).resolve(prefix + "a-3");
    }

    @Test
    void 마스킹된_지번_표본은_지오코딩_없이_후보에서_제외된다() {
        init();
        Property property = officetel(25.0);
        when(kakaoRegionCodeClient.resolve(PROPERTY_LAT, PROPERTY_LNG)).thenReturn(resolvedRegion());

        TradeTransactionSample ok1 = sample("c-1", 950_000_000L, 25.0);
        TradeTransactionSample ok2 = sample("c-2", 1_000_000_000L, 25.0);
        TradeTransactionSample ok3 = sample("c-3", 1_050_000_000L, 25.0);
        // 단독/다가구 매매는 jibun이 null이 아니라 마스킹돼서 오므로, MolitTradeClientImpl이 이미
        // null로 정규화해서 넘긴다고 가정한다(jibunAddress == null인 표본).
        TradeTransactionSample masked = sample(null, 9_000_000_000L, 25.0);
        stubFirstMonthOnly(List.of(ok1, ok2, ok3, masked));

        String prefix = "서울특별시 종로구 청운동 ";
        when(kakaoAddressClient.resolve(prefix + "c-1")).thenReturn(resolvedAt(PROPERTY_LAT, PROPERTY_LNG));
        when(kakaoAddressClient.resolve(prefix + "c-2")).thenReturn(resolvedAt(PROPERTY_LAT, PROPERTY_LNG));
        when(kakaoAddressClient.resolve(prefix + "c-3")).thenReturn(resolvedAt(PROPERTY_LAT, PROPERTY_LNG));

        MarketSaleComparisonResponse response = service.compare(property);

        assertThat(response.status()).isEqualTo("AVAILABLE");
        assertThat(response.sampleCount()).isEqualTo(3);
        assertThat(response.referencePrice()).isEqualTo(1_000_000_000L);
    }
}
