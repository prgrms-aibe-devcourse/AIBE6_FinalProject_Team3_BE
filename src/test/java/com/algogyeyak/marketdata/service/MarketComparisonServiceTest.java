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
import com.algogyeyak.marketdata.config.MarketComparisonProperties;
import com.algogyeyak.marketdata.dto.MarketComparisonResponse;
import com.algogyeyak.marketdata.dto.MarketComparisonUnavailableReason;
import com.algogyeyak.marketdata.dto.SamplePriceHighlight;
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
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;

@ExtendWith(MockitoExtension.class)
class MarketComparisonServiceTest {

    @Mock
    private KakaoRegionCodeClient kakaoRegionCodeClient;

    @Mock
    private KakaoAddressClient kakaoAddressClient;

    @Mock
    private MolitRentClient molitRentClient;

    // getCachedOnly()는 @Cacheable 프록시가 아니라 CacheManager를 직접 읽으므로, 실제 캐시 동작이
    // 필요한 테스트를 위해 Mockito mock 대신 인메모리 구현체를 쓴다(get/put이 실제로 동작해야
    // "캐시 히트/미스"를 검증할 수 있음).
    private final CacheManager cacheManager = new ConcurrentMapCacheManager("marketComparison");

    private MarketComparisonService service;

    private static final double PROPERTY_LAT = 37.5665;
    private static final double PROPERTY_LNG = 126.9780;
    private static final String LAWD_CD = "11110";

    // application.yml의 market-data.comparison.* 기본값과 동일하게 맞춰둔다.
    private static final MarketComparisonProperties PROPERTIES =
            new MarketComparisonProperties(300, 600, 3, 0.2, 6, 30);

    private void init() {
        service = new MarketComparisonService(
                kakaoRegionCodeClient, kakaoAddressClient, molitRentClient, PROPERTIES, cacheManager);
    }

    private Property jeonseOfficetel(long deposit, double area) {
        Property property = Property.builder()
                .userId(1L)
                .title("테스트 매물")
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
                .title("테스트 매물")
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
        assertThat(response.reason()).isEqualTo(MarketComparisonUnavailableReason.TRANSACTION_TYPE_UNSUPPORTED);
        verifyNoInteractions(kakaoRegionCodeClient, kakaoAddressClient, molitRentClient);
    }

    @Test
    void 단독다가구는_MOLIT_호출_없이_즉시_판정불가를_반환한다() {
        init();
        Property property = Property.builder()
                .userId(1L)
                .title("테스트 매물")
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
        assertThat(response.reason()).isEqualTo(MarketComparisonUnavailableReason.PROPERTY_TYPE_UNSUPPORTED);
        verifyNoInteractions(kakaoRegionCodeClient, kakaoAddressClient, molitRentClient);
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

        MarketComparisonResponse response = service.compare(property);

        assertThat(response.status()).isEqualTo("UNAVAILABLE");
        assertThat(response.reason()).isEqualTo(MarketComparisonUnavailableReason.ADDRESS_INFO_MISSING);
        verifyNoInteractions(kakaoRegionCodeClient, molitRentClient);
    }

    @Test
    void 법정동코드_조회에_실패하면_판정불가를_반환한다() {
        init();
        Property property = jeonseOfficetel(200_000_000L, 25.0);
        when(kakaoRegionCodeClient.resolve(PROPERTY_LAT, PROPERTY_LNG)).thenReturn(RegionCodeResult.unresolved());

        MarketComparisonResponse response = service.compare(property);

        assertThat(response.status()).isEqualTo("UNAVAILABLE");
        assertThat(response.reason()).isEqualTo(MarketComparisonUnavailableReason.ADDRESS_INFO_MISSING);
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
        assertThat(response.radiusMeters()).isEqualTo(300);
        assertThat(response.areaErrorRate()).isEqualTo(0.2);
        assertThat(response.lookbackMonths()).isEqualTo(6);
        assertThat(response.reason()).isNull();
        // 5차 멘토링 피드백 7-2 - 기준가 계산에 실제로 쓰인 표본이 그대로 담겨야 한다.
        assertThat(response.samples()).hasSize(3);
    }

    // 5차 멘토링 피드백 7-2 - AVAILABLE 응답의 samples가 실제 계산에 쓰인 표본의 상세 정보(건물명/주소/
    // 계약일/보증금/면적)를 담고, 최신 계약일 순으로 정렬돼 내려가는지 검증한다.
    @Test
    void AVAILABLE_응답의_samples는_기준가_계산에_쓰인_표본을_최신_계약일순으로_담는다() {
        init();
        Property property = jeonseOfficetel(200_000_000L, 25.0);
        when(kakaoRegionCodeClient.resolve(PROPERTY_LAT, PROPERTY_LNG)).thenReturn(resolvedRegion());

        RentTransactionSample oldest = RentTransactionSample.builder()
                .propertyType(PropertyType.OFFICETEL).buildingName("테스트오피스텔")
                .jibunAddress("1-1").legalDongCode(LAWD_CD).legalDongName("청운동")
                .dealDate(LocalDate.of(2026, 4, 1)).depositWon(180_000_000L).monthlyRentWon(0L).areaSqm(24.0)
                .build();
        RentTransactionSample newest = RentTransactionSample.builder()
                .propertyType(PropertyType.OFFICETEL).buildingName("테스트오피스텔")
                .jibunAddress("1-2").legalDongCode(LAWD_CD).legalDongName("청운동")
                .dealDate(LocalDate.of(2026, 6, 15)).depositWon(200_000_000L).monthlyRentWon(0L).areaSqm(25.0)
                .build();
        RentTransactionSample middle = RentTransactionSample.builder()
                .propertyType(PropertyType.OFFICETEL).buildingName("테스트오피스텔")
                .jibunAddress("1-3").legalDongCode(LAWD_CD).legalDongName("청운동")
                .dealDate(LocalDate.of(2026, 5, 10)).depositWon(220_000_000L).monthlyRentWon(0L).areaSqm(26.0)
                .build();
        stubFirstMonthOnly(List.of(oldest, newest, middle));
        when(kakaoAddressClient.resolve(anyString())).thenReturn(resolvedAt(PROPERTY_LAT, PROPERTY_LNG));

        MarketComparisonResponse response = service.compare(property);

        assertThat(response.samples()).hasSize(3);
        // 최신 계약일(2026-06-15) 순으로 정렬돼야 한다.
        assertThat(response.samples()).extracting("dealDate")
                .containsExactly("2026-06-15", "2026-05-10", "2026-04-01");
        assertThat(response.samples().get(0).buildingName()).isEqualTo("테스트오피스텔");
        assertThat(response.samples().get(0).address()).isEqualTo("서울특별시 종로구 청운동 1-2");
        assertThat(response.samples().get(0).depositWon()).isEqualTo(200_000_000L);
        assertThat(response.samples().get(0).areaSqm()).isEqualTo(25.0);
        // 표본이 5건 이하라 추릴 필요가 없었으면 priceHighlight는 전부 null이어야 한다.
        assertThat(response.samples()).extracting("priceHighlight").containsOnlyNulls();
    }

    // 표본이 대표 노출 상한(5건)을 넘으면 최고가/최저가/최근순으로 추려야 한다(사용자 피드백,
    // 2026-08-21) - 다 보여주면 응답이 무거워지고 사용자도 다 읽기 부담스럽다는 이유.
    @Test
    void 표본이_5건을_넘으면_최고가_최저가_최근순으로_5건만_담는다() {
        init();
        Property property = jeonseOfficetel(200_000_000L, 25.0);
        when(kakaoRegionCodeClient.resolve(PROPERTY_LAT, PROPERTY_LNG)).thenReturn(resolvedRegion());

        RentTransactionSample lowest = txSample("1-1", LocalDate.of(2026, 1, 1), 150_000_000L);
        RentTransactionSample highest = txSample("1-2", LocalDate.of(2026, 2, 1), 300_000_000L);
        RentTransactionSample middleExcluded = txSample("1-3", LocalDate.of(2026, 3, 1), 200_000_000L);
        RentTransactionSample recent1 = txSample("1-4", LocalDate.of(2026, 4, 1), 210_000_000L);
        RentTransactionSample recent2 = txSample("1-5", LocalDate.of(2026, 5, 1), 190_000_000L);
        RentTransactionSample mostRecent = txSample("1-6", LocalDate.of(2026, 6, 1), 205_000_000L);
        stubFirstMonthOnly(List.of(lowest, highest, middleExcluded, recent1, recent2, mostRecent));
        when(kakaoAddressClient.resolve(anyString())).thenReturn(resolvedAt(PROPERTY_LAT, PROPERTY_LNG));

        MarketComparisonResponse response = service.compare(property);

        // 총 표본 수(sampleCount)는 6건 그대로지만, 노출용 samples는 대표 5건으로 추려진다.
        assertThat(response.sampleCount()).isEqualTo(6);
        assertThat(response.samples()).hasSize(5);
        // 최근순으로 채워지는 3건(mostRecent/recent2/recent1) + 최고가(highest) + 최저가(lowest) -
        // middleExcluded(200M, 3월)만 어느 기준에도 안 걸려 빠진다. 최종 출력은 항상 최신 계약일순.
        assertThat(response.samples()).extracting("dealDate")
                .containsExactly("2026-06-01", "2026-05-01", "2026-04-01", "2026-02-01", "2026-01-01");
        assertThat(response.samples()).extracting("depositWon")
                .containsExactly(205_000_000L, 190_000_000L, 210_000_000L, 300_000_000L, 150_000_000L);
        // 목록 순서(최신순)와 선정 사유(최고가/최저가)가 다르다 보니, FE가 배지를 붙일 수 있도록
        // priceHighlight로 어떤 표본이 왜 뽑혔는지 표시해야 한다 - 나머지 표본(최근순으로 뽑힌 것)은 null.
        assertThat(response.samples()).extracting("priceHighlight")
                .containsExactly(null, null, null, SamplePriceHighlight.HIGHEST, SamplePriceHighlight.LOWEST);
    }

    private RentTransactionSample txSample(String jibun, LocalDate dealDate, long depositWon) {
        return RentTransactionSample.builder()
                .propertyType(PropertyType.OFFICETEL).buildingName("테스트오피스텔")
                .jibunAddress(jibun).legalDongCode(LAWD_CD).legalDongName("청운동")
                .dealDate(dealDate).depositWon(depositWon).monthlyRentWon(0L).areaSqm(25.0)
                .build();
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
        assertThat(response.radiusMeters()).isEqualTo(600);
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
        assertThat(response.reason()).isEqualTo(MarketComparisonUnavailableReason.INSUFFICIENT_SAMPLE);
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

    @Test
    void getCachedOnly는_캐시가_없으면_계산을_트리거하지_않고_NOT_YET_CALCULATED를_반환한다() {
        init();

        MarketComparisonResponse response = service.getCachedOnly(1L);

        assertThat(response.status()).isEqualTo("UNAVAILABLE");
        assertThat(response.reason()).isEqualTo(MarketComparisonUnavailableReason.NOT_YET_CALCULATED);
        // 목록 조회 성능 개선의 핵심 - 캐시 미스여도 국토부/카카오 API를 절대 호출하지 않아야 한다.
        verifyNoInteractions(kakaoRegionCodeClient, kakaoAddressClient, molitRentClient);
    }

    @Test
    void getCachedOnly는_캐시에_이미_있으면_그대로_반환하고_계산을_트리거하지_않는다() {
        init();
        MarketComparisonResponse cached = MarketComparisonResponse.available(
                28_000_000L, 0.07, 5, "2026-06-20", 300, 0.2, 6, List.of()
        );
        // compare()가 @Cacheable 프록시를 거쳐야 실제로 채워지는 캐시라, 프록시 없이 직접 생성한 이
        // 테스트에서는 채워진 캐시 상태를 수동으로 흉내낸다(등록/수정/상세조회에서 compare()가 이미
        // 채워놓은 캐시를 목록 조회가 재사용하는 상황과 동일).
        cacheManager.getCache("marketComparison").put(1L, cached);

        MarketComparisonResponse response = service.getCachedOnly(1L);

        assertThat(response).isEqualTo(cached);
        verifyNoInteractions(kakaoRegionCodeClient, kakaoAddressClient, molitRentClient);
    }
}
