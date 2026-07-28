package com.algogyeyak.marketdata.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.algogyeyak.marketdata.client.MolitRentClient;
import com.algogyeyak.marketdata.client.RentTransactionSample;
import com.algogyeyak.marketdata.dto.MarketComparisonResponse;
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
class MarketComparisonServiceTest {

    @Mock
    private KakaoRegionCodeClient kakaoRegionCodeClient;

    @Mock
    private KakaoAddressClient kakaoAddressClient;

    @Mock
    private MolitRentClient molitRentClient;

    private MarketComparisonService service;

    private static final double PROPERTY_LAT = 37.5665;
    private static final double PROPERTY_LNG = 126.9780;
    private static final String LAWD_CD = "11110";

    private void init() {
        service = new MarketComparisonService(kakaoRegionCodeClient, kakaoAddressClient, molitRentClient);
    }

    private Property jeonseOfficetel(long deposit, double area) {
        Property property = Property.builder()
                .userId(1L)
                .propertyType(PropertyType.OFFICETEL)
                .transactionType(TransactionType.JEONSE)
                .deposit(deposit)
                .monthlyRent(null)
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

    private RentTransactionSample sample(String jibun, long depositWon, long monthlyRentWon, double area) {
        return RentTransactionSample.builder()
                .propertyType(PropertyType.OFFICETEL)
                .buildingName("테스트오피스텔")
                .jibunAddress(jibun)
                .legalDongCode(LAWD_CD)
                .legalDongName("청운동")
                .dealDate(LocalDate.of(2026, 6, 15))
                .depositWon(depositWon)
                .monthlyRentWon(monthlyRentWon)
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

    private void stubFirstMonthOnly(List<RentTransactionSample> samples) {
        when(molitRentClient.fetch(eq(PropertyType.OFFICETEL), eq(LAWD_CD), any(YearMonth.class)))
                .thenReturn(samples, List.of(), List.of(), List.of(), List.of(), List.of());
    }

    @Test
    void 월세_매물은_MOLIT_호출_없이_즉시_판정불가를_반환한다() {
        init();
        Property property = Property.builder()
                .userId(1L)
                .propertyType(PropertyType.OFFICETEL)
                .transactionType(TransactionType.MONTHLY_RENT)
                .deposit(10_000_000L)
                .monthlyRent(500_000L)
                .area(25.0)
                .build();
        property.assignAddress(PropertyAddress.builder()
                .jibunAddress("서울특별시 종로구 세종로 1")
                .latitude(PROPERTY_LAT)
                .longitude(PROPERTY_LNG)
                .build());

        MarketComparisonResponse response = service.compare(property);

        assertThat(response.status()).isEqualTo("UNAVAILABLE");
        assertThat(response.message()).contains("월세");
        verifyNoInteractions(kakaoRegionCodeClient, kakaoAddressClient, molitRentClient);
    }

    @Test
    void 단독다가구는_MOLIT_호출_없이_즉시_판정불가를_반환한다() {
        init();
        Property property = Property.builder()
                .userId(1L)
                .propertyType(PropertyType.DETACHED_HOUSE)
                .transactionType(TransactionType.JEONSE)
                .deposit(200_000_000L)
                .area(50.0)
                .build();
        property.assignAddress(PropertyAddress.builder()
                .jibunAddress("서울특별시 종로구 충신동 1")
                .latitude(PROPERTY_LAT)
                .longitude(PROPERTY_LNG)
                .build());

        MarketComparisonResponse response = service.compare(property);

        assertThat(response.status()).isEqualTo("UNAVAILABLE");
        assertThat(response.message()).contains("공인중개사");
        verifyNoInteractions(kakaoRegionCodeClient, kakaoAddressClient, molitRentClient);
    }

    @Test
    void 좌표가_없으면_판정불가를_반환한다() {
        init();
        Property property = Property.builder()
                .userId(1L)
                .propertyType(PropertyType.OFFICETEL)
                .transactionType(TransactionType.JEONSE)
                .deposit(200_000_000L)
                .area(25.0)
                .build();
        // 주소를 아예 할당하지 않음 -> address == null

        MarketComparisonResponse response = service.compare(property);

        assertThat(response.status()).isEqualTo("UNAVAILABLE");
        verifyNoInteractions(kakaoRegionCodeClient, molitRentClient);
    }

    @Test
    void 법정동코드_조회에_실패하면_판정불가를_반환한다() {
        init();
        Property property = jeonseOfficetel(200_000_000L, 25.0);
        when(kakaoRegionCodeClient.resolve(PROPERTY_LAT, PROPERTY_LNG)).thenReturn(RegionCodeResult.unresolved());

        MarketComparisonResponse response = service.compare(property);

        assertThat(response.status()).isEqualTo("UNAVAILABLE");
        verifyNoInteractions(molitRentClient);
    }

    @Test
    void 반경300m_이내_표본이_3건이면_중앙값으로_AVAILABLE을_반환한다() {
        init();
        Property property = jeonseOfficetel(200_000_000L, 25.0);
        when(kakaoRegionCodeClient.resolve(PROPERTY_LAT, PROPERTY_LNG)).thenReturn(resolvedRegion());

        List<RentTransactionSample> samples = List.of(
                sample("1-1", 180_000_000L, 0L, 24.0),
                sample("1-2", 200_000_000L, 0L, 25.0),
                sample("1-3", 220_000_000L, 0L, 26.0)
        );
        stubFirstMonthOnly(samples);

        // 세 후보 모두 매물과 동일 좌표(0m)로 지오코딩되도록 스텁 - 300m 이내
        when(kakaoAddressClient.resolve(anyString())).thenReturn(resolvedAt(PROPERTY_LAT, PROPERTY_LNG));

        MarketComparisonResponse response = service.compare(property);

        assertThat(response.status()).isEqualTo("AVAILABLE");
        assertThat(response.sampleCount()).isEqualTo(3);
        assertThat(response.referencePrice()).isEqualTo(200_000_000L);
        assertThat(response.differenceRate()).isEqualTo(0.0);
    }

    @Test
    void _300m_이내가_부족해_600m로_확장하는_경우를_검증한다() {
        init();
        Property property = jeonseOfficetel(200_000_000L, 25.0);
        when(kakaoRegionCodeClient.resolve(PROPERTY_LAT, PROPERTY_LNG)).thenReturn(resolvedRegion());

        RentTransactionSample near = sample("near-1", 190_000_000L, 0L, 25.0);
        RentTransactionSample mid1 = sample("mid-1", 200_000_000L, 0L, 25.0);
        RentTransactionSample mid2 = sample("mid-2", 210_000_000L, 0L, 25.0);
        stubFirstMonthOnly(List.of(near, mid1, mid2));

        double midLat = PROPERTY_LAT + 0.0035; // 약 389m: 300m 밖, 600m 이내

        // geocodeAll은 실제로 "{시군구} {법정동} {지번}" 형태의 조합 문자열로 조회한다
        String prefix = "서울특별시 종로구 청운동 ";
        when(kakaoAddressClient.resolve(prefix + "near-1")).thenReturn(resolvedAt(PROPERTY_LAT, PROPERTY_LNG));
        when(kakaoAddressClient.resolve(prefix + "mid-1")).thenReturn(resolvedAt(midLat, PROPERTY_LNG));
        when(kakaoAddressClient.resolve(prefix + "mid-2")).thenReturn(resolvedAt(midLat, PROPERTY_LNG));

        MarketComparisonResponse response = service.compare(property);

        assertThat(response.status()).isEqualTo("AVAILABLE");
        assertThat(response.sampleCount()).isEqualTo(3);
        assertThat(response.referencePrice()).isEqualTo(200_000_000L);
    }

    @Test
    void _600m_이내에서도_표본이_3건_미만이면_판정불가를_반환한다() {
        init();
        Property property = jeonseOfficetel(200_000_000L, 25.0);
        when(kakaoRegionCodeClient.resolve(PROPERTY_LAT, PROPERTY_LNG)).thenReturn(resolvedRegion());

        RentTransactionSample near = sample("near-1", 190_000_000L, 0L, 25.0);
        RentTransactionSample mid = sample("mid-1", 200_000_000L, 0L, 25.0);
        stubFirstMonthOnly(List.of(near, mid));

        String prefix = "서울특별시 종로구 청운동 ";
        when(kakaoAddressClient.resolve(prefix + "near-1")).thenReturn(resolvedAt(PROPERTY_LAT, PROPERTY_LNG));
        when(kakaoAddressClient.resolve(prefix + "mid-1"))
                .thenReturn(resolvedAt(PROPERTY_LAT + 0.0035, PROPERTY_LNG));

        MarketComparisonResponse response = service.compare(property);

        assertThat(response.status()).isEqualTo("UNAVAILABLE");
        assertThat(response.message()).contains("부족");
    }

    @Test
    void 면적오차가_20퍼센트를_넘는_표본은_후보에서_제외된다() {
        init();
        Property property = jeonseOfficetel(200_000_000L, 25.0);
        when(kakaoRegionCodeClient.resolve(PROPERTY_LAT, PROPERTY_LNG)).thenReturn(resolvedRegion());

        // 25 ±20% = 20~30. 아래 하나만 범위를 벗어남(40) -> 후보에서 제외되어 표본부족
        RentTransactionSample within1 = sample("a-1", 190_000_000L, 0L, 24.0);
        RentTransactionSample within2 = sample("a-2", 200_000_000L, 0L, 26.0);
        RentTransactionSample outOfRange = sample("a-3", 999_000_000L, 0L, 40.0);
        stubFirstMonthOnly(List.of(within1, within2, outOfRange));

        String prefix = "서울특별시 종로구 청운동 ";
        when(kakaoAddressClient.resolve(prefix + "a-1")).thenReturn(resolvedAt(PROPERTY_LAT, PROPERTY_LNG));
        when(kakaoAddressClient.resolve(prefix + "a-2")).thenReturn(resolvedAt(PROPERTY_LAT, PROPERTY_LNG));

        MarketComparisonResponse response = service.compare(property);

        // 면적오차를 벗어난 표본이 제외되어 2건만 남아 표본부족(3건 미만)으로 판정불가여야 한다
        assertThat(response.status()).isEqualTo("UNAVAILABLE");
        verify(kakaoAddressClient, never()).resolve(prefix + "a-3");
    }

    @Test
    void 월세_거래_내역은_전세_비교_후보에서_제외된다() {
        init();
        Property property = jeonseOfficetel(200_000_000L, 25.0);
        when(kakaoRegionCodeClient.resolve(PROPERTY_LAT, PROPERTY_LNG)).thenReturn(resolvedRegion());

        RentTransactionSample jeonse1 = sample("b-1", 190_000_000L, 0L, 25.0);
        RentTransactionSample jeonse2 = sample("b-2", 200_000_000L, 0L, 25.0);
        RentTransactionSample monthlyRentSample = sample("b-3", 10_000_000L, 500_000L, 25.0); // 월세 거래 - 제외 대상

        stubFirstMonthOnly(List.of(jeonse1, jeonse2, monthlyRentSample));

        String prefix = "서울특별시 종로구 청운동 ";
        when(kakaoAddressClient.resolve(prefix + "b-1")).thenReturn(resolvedAt(PROPERTY_LAT, PROPERTY_LNG));
        when(kakaoAddressClient.resolve(prefix + "b-2")).thenReturn(resolvedAt(PROPERTY_LAT, PROPERTY_LNG));

        MarketComparisonResponse response = service.compare(property);

        assertThat(response.status()).isEqualTo("UNAVAILABLE");
        verify(kakaoAddressClient, never()).resolve(prefix + "b-3");
    }

    @Test
    void 지오코딩에_실패한_후보는_표본에서_제외되고_나머지로_계산된다() {
        init();
        Property property = jeonseOfficetel(200_000_000L, 25.0);
        when(kakaoRegionCodeClient.resolve(PROPERTY_LAT, PROPERTY_LNG)).thenReturn(resolvedRegion());

        RentTransactionSample ok1 = sample("c-1", 190_000_000L, 0L, 25.0);
        RentTransactionSample ok2 = sample("c-2", 200_000_000L, 0L, 25.0);
        RentTransactionSample ok3 = sample("c-3", 210_000_000L, 0L, 25.0);
        // 국토부 지번 표기가 카카오 검색과 안 맞아 지오코딩 자체가 실패하는 케이스를 재현
        RentTransactionSample geocodeFails = sample("산1-2", 999_000_000L, 0L, 25.0);
        stubFirstMonthOnly(List.of(ok1, ok2, ok3, geocodeFails));

        String prefix = "서울특별시 종로구 청운동 ";
        when(kakaoAddressClient.resolve(prefix + "c-1")).thenReturn(resolvedAt(PROPERTY_LAT, PROPERTY_LNG));
        when(kakaoAddressClient.resolve(prefix + "c-2")).thenReturn(resolvedAt(PROPERTY_LAT, PROPERTY_LNG));
        when(kakaoAddressClient.resolve(prefix + "c-3")).thenReturn(resolvedAt(PROPERTY_LAT, PROPERTY_LNG));
        when(kakaoAddressClient.resolve(prefix + "산1-2")).thenReturn(AddressResolutionResult.unresolved());

        MarketComparisonResponse response = service.compare(property);

        // 지오코딩 실패 건(999_000_000)은 표본에서 빠지고, 나머지 3건의 중앙값으로만 계산돼야 한다
        assertThat(response.status()).isEqualTo("AVAILABLE");
        assertThat(response.sampleCount()).isEqualTo(3);
        assertThat(response.referencePrice()).isEqualTo(200_000_000L);
        verify(kakaoAddressClient).resolve(prefix + "산1-2");
    }
}
