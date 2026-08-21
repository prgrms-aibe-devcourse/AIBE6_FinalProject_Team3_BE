package com.algogyeyak.marketdata.service;

import com.algogyeyak.marketdata.client.MolitRentClient;
import com.algogyeyak.marketdata.client.RentTransactionSample;
import com.algogyeyak.marketdata.config.MarketComparisonProperties;
import com.algogyeyak.marketdata.dto.MarketComparisonResponse;
import com.algogyeyak.marketdata.dto.MarketComparisonUnavailableReason;
import com.algogyeyak.marketdata.dto.MarketTransactionSampleResponse;
import com.algogyeyak.marketdata.dto.SamplePriceHighlight;
import com.algogyeyak.marketdata.util.GeoDistanceCalculator;
import com.algogyeyak.property.client.AddressResolutionResult;
import com.algogyeyak.property.client.KakaoAddressClient;
import com.algogyeyak.property.client.KakaoRegionCodeClient;
import com.algogyeyak.property.client.RegionCodeResult;
import com.algogyeyak.property.entity.Property;
import com.algogyeyak.property.entity.PropertyAddress;
import com.algogyeyak.property.entity.PropertyType;
import com.algogyeyak.property.entity.TransactionType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 매물과 국토부 실거래가를 비교해 시세 대비 위치를 판정한다.
 *
 * 판정 절차:
 * 1. 월세는 시세 비교 대상이 아니므로 항상 UNAVAILABLE.
 * 2. 단독/다가구는 국토부 API가 지번을 제공하지 않아(개인정보보호) 정확한 반경 계산이
 *    불가능하므로 항상 UNAVAILABLE (동일 법정동 근사치로 "인근"이라고 표시하는 것은
 *    오히려 신뢰도를 해칠 수 있다고 판단해 v1에서는 채택하지 않음).
 * 3. 오피스텔/연립다세대(다세대)는 매물 좌표의 법정동코드(LAWD_CD)로 최근 6개월치 실거래를
 *    조회하고, 전세 거래만 남긴 뒤 면적오차 ±20% 이내로 1차 필터링한다.
 * 4. 남은 후보의 지번주소를 Kakao로 지오코딩(주소 문자열 단위로 캐싱)해 실제 좌표를 구하고,
 *    하버사인 거리로 300m 이내 표본이 3건 이상이면 그걸 사용하고, 부족하면 600m로 확장한다.
 * 5. 같은 법정동 내에서도 600m 이내 3건을 못 채우면 인접 법정동으로 넘어가지 않고
 *    UNAVAILABLE 처리한다 (v1 한계, 부정확한 "인근" 비교보다 안전한 쪽을 택함).
 *
 * compare()의 결과는 propertyId를 키로 Redis에 캐싱된다(cacheNames = "marketComparison",
 * TTL은 market-data.comparison.cache-ttl-minutes) - 매물 상세조회를 반복할 때마다 국토부/카카오
 * API를 다시 호출하지 않기 위함. 매물 가격/면적이 바뀌면 결과가 달라지므로,
 * PropertyService.update()가 재계산 직전에 evictCache()로 해당 매물의 캐시를 명시적으로 비운다.
 * FakeListingSignalService.checkAndSave(Property)도 PRICE_ANOMALY 판정 직전에 한 번 더
 * evictCache()를 부른다 - PropertyService.update() 경로를 거치지 않고 이 메서드가 호출되는
 * 경우(재계산 배치 등)까지 캐시가 30분간 낡은 채로 남는 걸 막기 위함(evict는 멱등이라 안전).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MarketComparisonService {

    private final KakaoRegionCodeClient kakaoRegionCodeClient;
    private final KakaoAddressClient kakaoAddressClient;
    private final MolitRentClient molitRentClient;
    private final MarketComparisonProperties properties;
    private final CacheManager cacheManager;

    // 지번주소 -> 지오코딩 결과 캐시. 같은 건물에서 여러 건 거래된 경우가 많아 캐시 효과가 크다.
    // (Redis가 아니라 이 빈의 인메모리 필드 - 프로세스 재시작 시 초기화됨. Redis 캐싱 대상은
    // compare()의 최종 결과뿐이다.)
    private final Map<String, AddressResolutionResult> geocodeCache = new ConcurrentHashMap<>();

    @Cacheable(cacheNames = "marketComparison", key = "#property.id")
    public MarketComparisonResponse compare(Property property) {
        if (property.getTransactionType() == TransactionType.MONTHLY_RENT) {
            return MarketComparisonResponse.unavailable(
                    MarketComparisonUnavailableReason.TRANSACTION_TYPE_UNSUPPORTED, "월세는 시세 비교 대상이 아니에요.");
        }

        if (property.getPropertyType() == PropertyType.DETACHED_HOUSE) {
            return MarketComparisonResponse.unavailable(
                    MarketComparisonUnavailableReason.PROPERTY_TYPE_UNSUPPORTED,
                    "단독/다가구는 실거래 상세 위치가 비공개라 반경 비교가 어려워요. "
                            + "정확한 시세는 인근 공인중개사에 문의해보세요."
            );
        }

        PropertyAddress address = property.getAddress();
        if (address == null || address.getLatitude() == null || address.getLongitude() == null) {
            return MarketComparisonResponse.unavailable(
                    MarketComparisonUnavailableReason.ADDRESS_INFO_MISSING, "매물 좌표 정보가 없어 시세 비교가 어려워요.");
        }

        RegionCodeResult regionCode = kakaoRegionCodeClient.resolve(address.getLatitude(), address.getLongitude());
        if (!regionCode.isResolved()) {
            return MarketComparisonResponse.unavailable(
                    MarketComparisonUnavailableReason.ADDRESS_INFO_MISSING, "지역 정보를 확인하지 못해 시세 비교가 어려워요.");
        }

        String sggPrefix = extractSggPrefix(regionCode.getRegionName());

        List<RentTransactionSample> candidates = fetchRecentTransactions(property.getPropertyType(), regionCode.getLawdCd());

        List<RentTransactionSample> areaFiltered = candidates.stream()
                .filter(RentTransactionSample::isJeonse)
                .filter(sample -> sample.getAreaSqm() != null && isWithinAreaTolerance(property.getArea(), sample.getAreaSqm()))
                .toList();

        List<GeocodedSample> geocoded = geocodeAll(areaFiltered, sggPrefix);

        int radiusUsed = properties.radiusNearMeters();
        List<GeocodedSample> used = filterByRadius(geocoded, address, properties.radiusNearMeters());
        if (used.size() < properties.minSampleCount()) {
            radiusUsed = properties.radiusFarMeters();
            List<GeocodedSample> withinFar = filterByRadius(geocoded, address, properties.radiusFarMeters());
            if (withinFar.size() >= properties.minSampleCount()) {
                used = withinFar;
            } else {
                return MarketComparisonResponse.unavailable(
                        MarketComparisonUnavailableReason.INSUFFICIENT_SAMPLE, "인근 실거래 데이터가 부족해 시세 비교가 어려워요.");
            }
        }

        List<Long> deposits = used.stream()
                .map(g -> g.sample().getDepositWon())
                .sorted()
                .toList();
        long referencePrice = median(deposits);
        double differenceRate = (property.getDeposit() - referencePrice) / (double) referencePrice;
        LocalDate latestDealDate = used.stream()
                .map(g -> g.sample().getDealDate())
                .max(Comparator.naturalOrder())
                .orElse(null);

        return MarketComparisonResponse.available(
                referencePrice,
                differenceRate,
                used.size(),
                latestDealDate != null ? latestDealDate.toString() : null,
                radiusUsed,
                properties.areaErrorRate(),
                properties.lookbackMonths(),
                buildSampleResponses(used, sggPrefix)
        );
    }

    private List<RentTransactionSample> fetchRecentTransactions(PropertyType propertyType, String lawdCd) {
        List<RentTransactionSample> result = new ArrayList<>();
        YearMonth current = YearMonth.now();
        for (int i = 0; i < properties.lookbackMonths(); i++) {
            result.addAll(molitRentClient.fetch(propertyType, lawdCd, current.minusMonths(i)));
        }
        return result;
    }

    private boolean isWithinAreaTolerance(double propertyArea, double sampleArea) {
        if (propertyArea <= 0) {
            return false;
        }
        double errorRate = Math.abs(sampleArea - propertyArea) / propertyArea;
        return errorRate <= properties.areaErrorRate();
    }

    private List<GeocodedSample> geocodeAll(List<RentTransactionSample> samples, String sggPrefix) {
        List<GeocodedSample> result = new ArrayList<>();
        int failedCount = 0;
        for (RentTransactionSample sample : samples) {
            if (sample.getJibunAddress() == null) {
                continue;
            }
            String fullAddress = buildFullAddress(sample, sggPrefix);

            AddressResolutionResult resolution = geocodeCache.computeIfAbsent(
                    fullAddress, kakaoAddressClient::resolve
            );

            if (resolution.isResolved()) {
                result.add(new GeocodedSample(sample, resolution.getLatitude(), resolution.getLongitude()));
            } else {
                failedCount++;
                // 국토부 지번 표기(산번지 등)가 카카오 검색과 안 맞는 경우가 있어, 실패 패턴을 나중에
                // 확인할 수 있도록 조합 주소를 그대로 남겨둔다. 실패 건은 표본에서만 제외되고
                // 비교 자체를 막지는 않는다 (표본 수가 줄어드는 방향으로만 작동해 안전한 실패).
                log.debug("실거래 지오코딩 실패 - 조합주소: {}", fullAddress);
            }
        }
        if (failedCount > 0) {
            log.info("실거래 지오코딩 실패 {}건 (전체 후보 {}건 중) - 표본에서 제외됨", failedCount, samples.size());
        }
        return result;
    }

    /**
     * "서울특별시 종로구" + "청운동" + "1-1" -> "서울특별시 종로구 청운동 1-1" 형태의 조합 주소.
     * geocodeAll()의 지오코딩 대상 주소, 그리고 buildSampleResponses()의 사용자 노출용 주소가
     * 동일한 조합 규칙을 써야 하므로(동일 표본이면 같은 주소여야 함) 하나로 공유한다.
     */
    private String buildFullAddress(RentTransactionSample sample, String sggPrefix) {
        return sggPrefix + " " + sample.getLegalDongName() + " " + sample.getJibunAddress();
    }

    // 대단지 등에서 표본(used)이 많으면 사용자 노출용 응답이 무거워지고 다 읽기도 부담스러워서,
    // 대표성 있는 최대 이 개수만 추린다(사용자 피드백, 2026-08-21).
    private static final int MAX_EXPOSED_SAMPLES = 5;

    /**
     * 기준가(중앙값) 산출에 실제로 쓰인 표본(used) 중 대표 표본만 사용자 노출용 응답으로
     * 변환한다(5차 멘토링 피드백 7-2). 최종 목록은 항상 최신 계약일 순으로 정렬해 최근 거래부터
     * 보여준다 - 이때 최고가/최저가로 뽑힌 표본이 목록 중간에 섞여 있으면 "왜 여기 있지?" 하고
     * 혼란스러울 수 있어(사용자 피드백, 2026-08-21), priceHighlight로 선정 사유를 같이 내려준다.
     */
    private List<MarketTransactionSampleResponse> buildSampleResponses(List<GeocodedSample> used, String sggPrefix) {
        RepresentativeSamples representative = pickRepresentativeSamples(used);
        return representative.samples().stream()
                .sorted(Comparator.comparing((GeocodedSample g) -> g.sample().getDealDate()).reversed())
                .map(g -> new MarketTransactionSampleResponse(
                        g.sample().getBuildingName(),
                        buildFullAddress(g.sample(), sggPrefix),
                        g.sample().getDealDate().toString(),
                        g.sample().getDepositWon(),
                        g.sample().getAreaSqm(),
                        g == representative.highest() ? SamplePriceHighlight.HIGHEST
                                : g == representative.lowest() ? SamplePriceHighlight.LOWEST
                                : null
                ))
                .toList();
    }

    // pickRepresentativeSamples()가 어떤 표본을 최고가/최저가로 뽑았는지 buildSampleResponses()에
    // 같이 넘겨주기 위한 내부 홀더. 표본이 5건 이하라 추릴 필요가 없었으면 highest/lowest는 null
    // (전부 최근순 노출과 동일하게 취급되어 어떤 표본도 강조되지 않는다).
    private record RepresentativeSamples(List<GeocodedSample> samples, GeocodedSample highest, GeocodedSample lowest) {
    }

    /**
     * 표본이 MAX_EXPOSED_SAMPLES(5)건을 넘으면 최고가 1건 + 최저가 1건("이 가격대가 왜 이렇게
     * 나왔는지" 감을 잡게) + 나머지는 최근 계약일순으로 채워 대표 5건만 고른다. 동일 표본이 여러
     * 기준에 겹치면(예: 최고가이면서 동시에 최근 거래) 중복 없이 다음 후보로 넘어간다. 표본이
     * 5건 이하면 추릴 필요가 없어 그대로 전부 반환한다.
     */
    private RepresentativeSamples pickRepresentativeSamples(List<GeocodedSample> used) {
        if (used.size() <= MAX_EXPOSED_SAMPLES) {
            return new RepresentativeSamples(used, null, null);
        }

        GeocodedSample highest = used.stream()
                .max(Comparator.comparingLong(g -> g.sample().getDepositWon()))
                .orElseThrow();
        GeocodedSample lowest = used.stream()
                .min(Comparator.comparingLong(g -> g.sample().getDepositWon()))
                .filter(g -> g != highest)
                .orElse(null);

        List<GeocodedSample> selected = new ArrayList<>();
        selected.add(highest);
        if (lowest != null) {
            selected.add(lowest);
        }
        used.stream()
                .sorted(Comparator.comparing((GeocodedSample g) -> g.sample().getDealDate()).reversed())
                .filter(g -> !selected.contains(g))
                .limit(MAX_EXPOSED_SAMPLES - selected.size())
                .forEach(selected::add);

        return new RepresentativeSamples(selected, highest, lowest);
    }

    private List<GeocodedSample> filterByRadius(List<GeocodedSample> geocoded, PropertyAddress address, int radiusMeters) {
        return geocoded.stream()
                .filter(g -> GeoDistanceCalculator.distanceInMeters(
                        address.getLatitude(), address.getLongitude(), g.latitude(), g.longitude()
                ) <= radiusMeters)
                .toList();
    }

    /**
     * 매물 수정으로 가격/면적이 바뀌어 기존 캐시된 비교 결과가 더 이상 유효하지 않을 때 호출한다.
     * PropertyService.update()가 compare()를 다시 호출하기 직전에 사용 - 그렇지 않으면 캐시가
     * 그대로 남아 있어 수정 직후에도 예전 시세비교 결과가 반환된다.
     */
    @CacheEvict(cacheNames = "marketComparison", key = "#propertyId")
    public void evictCache(Long propertyId) {
        // 캐시 삭제는 어노테이션이 처리 - 메서드 본문은 필요 없음.
    }

    /**
     * 매물 목록 조회(PropertyService.getMyProperties())처럼 여러 매물을 한 번에 보여주는 화면에서
     * 쓴다 - compare()를 매물 수만큼 그대로 호출하면 국토부/카카오 API가 순차적으로 반복 호출돼
     * 목록 조회가 심하게 느려진다(최악의 경우 매물 1건당 지오코딩 요청이 여러 번). @Cacheable
     * 프록시(compare())를 거치지 않고 CacheManager를 직접 읽어서, 이미 캐시된 결과가 있으면 그대로
     * 반환하고 없으면(첫 조회이거나 TTL 만료) 계산을 트리거하지 않고 NOT_YET_CALCULATED로 응답한다.
     * 실제 계산은 여전히 등록/수정/상세조회(모두 단일 매물 호출이라 목록 크기에 증폭되지 않음)에서
     * compare()를 통해 이뤄지고, 그 결과가 이 캐시를 채운다.
     */
    public MarketComparisonResponse getCachedOnly(Long propertyId) {
        Cache cache = cacheManager.getCache("marketComparison");
        MarketComparisonResponse cached = cache != null ? cache.get(propertyId, MarketComparisonResponse.class) : null;
        if (cached != null) {
            return cached;
        }
        return MarketComparisonResponse.unavailable(
                MarketComparisonUnavailableReason.NOT_YET_CALCULATED,
                "아직 시세 비교가 계산되지 않았어요. 매물 상세 화면에서 확인해보세요.");
    }

    private long median(List<Long> sortedValues) {
        int size = sortedValues.size();
        int mid = size / 2;
        if (size % 2 == 0) {
            return Math.round((sortedValues.get(mid - 1) + sortedValues.get(mid)) / 2.0);
        }
        return sortedValues.get(mid);
    }

    /**
     * "서울특별시 종로구 청운동" -> "서울특별시 종로구".
     * 국토부 API가 연립다세대 응답에는 시군구명(sggNm)을 내려주지 않아서, 매물 자신의 좌표로
     * 이미 구해둔 지역명에서 시군구 접두어를 뽑아 각 거래의 법정동명(umdNm)+지번과 조합한다.
     * 어차피 같은 LAWD_CD로 조회했으므로 모든 후보가 동일 시군구에 속한다는 점을 이용한다.
     */
    private String extractSggPrefix(String regionName) {
        if (regionName == null) {
            return "";
        }
        String[] parts = regionName.trim().split("\\s+");
        if (parts.length <= 1) {
            return regionName;
        }
        return String.join(" ", List.of(parts).subList(0, parts.length - 1));
    }

    private record GeocodedSample(RentTransactionSample sample, double latitude, double longitude) {
    }
}
