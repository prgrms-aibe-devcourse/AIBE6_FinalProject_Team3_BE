package com.algogyeyak.marketdata.service;

import com.algogyeyak.marketdata.client.MolitRentClient;
import com.algogyeyak.marketdata.client.RentTransactionSample;
import com.algogyeyak.marketdata.config.MarketComparisonProperties;
import com.algogyeyak.marketdata.dto.MarketComparisonResponse;
import com.algogyeyak.marketdata.dto.MarketComparisonUnavailableReason;
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
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MarketComparisonService {

    private final KakaoRegionCodeClient kakaoRegionCodeClient;
    private final KakaoAddressClient kakaoAddressClient;
    private final MolitRentClient molitRentClient;
    private final MarketComparisonProperties properties;

    // 지번주소 -> 지오코딩 결과 캐시. 같은 건물에서 여러 건 거래된 경우가 많아 캐시 효과가 크다.
    private final Map<String, AddressResolutionResult> geocodeCache = new ConcurrentHashMap<>();

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
                radiusUsed
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
            String fullAddress = sggPrefix + " " + sample.getLegalDongName() + " " + sample.getJibunAddress();

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

    private List<GeocodedSample> filterByRadius(List<GeocodedSample> geocoded, PropertyAddress address, int radiusMeters) {
        return geocoded.stream()
                .filter(g -> GeoDistanceCalculator.distanceInMeters(
                        address.getLatitude(), address.getLongitude(), g.latitude(), g.longitude()
                ) <= radiusMeters)
                .toList();
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
