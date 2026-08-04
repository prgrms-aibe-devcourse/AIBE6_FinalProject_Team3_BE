package com.algogyeyak.marketdata.service;

import com.algogyeyak.marketdata.client.MolitTradeClient;
import com.algogyeyak.marketdata.client.TradeTransactionSample;
import com.algogyeyak.marketdata.config.MarketComparisonProperties;
import com.algogyeyak.marketdata.dto.MarketComparisonUnavailableReason;
import com.algogyeyak.marketdata.dto.MarketSaleComparisonResponse;
import com.algogyeyak.marketdata.util.GeoDistanceCalculator;
import com.algogyeyak.property.client.AddressResolutionResult;
import com.algogyeyak.property.client.KakaoAddressClient;
import com.algogyeyak.property.client.KakaoRegionCodeClient;
import com.algogyeyak.property.client.RegionCodeResult;
import com.algogyeyak.property.entity.Property;
import com.algogyeyak.property.entity.PropertyAddress;
import com.algogyeyak.property.entity.PropertyType;
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
 * 매물과 국토부 매매 실거래가를 비교해 매매 기준가(전세가율 계산용 분모)를 구한다.
 *
 * {@link MarketComparisonService}(전세 시세비교)와 지역/면적/반경/중앙값 매칭 알고리즘은 동일하되
 * 목적이 달라 의도적으로 별도 클래스로 둔다 - 이미 검증된 전세 비교 플로우(PriceAnomalyDetector가
 * 의존)를 안 건드리기 위함이며, 두 도메인(전세 시세비교 vs 전세가율)이 서로 다른 이유로 진화해도
 * 서로 간섭하지 않는다.
 *
 * MarketComparisonService와의 차이:
 * - 매물의 거래유형(전세/월세)과 무관하게 조회한다 - "이 매물이 팔리면 얼마인지"는 매물 자신이
 *   전세든 월세든 똑같이 필요한 정보이기 때문. 월세 매물 제외 여부는 이 서비스를 호출하는
 *   DepositSafetyCheckService가 판단할 몫이다.
 * - 단독/다가구를 하드코딩으로 제외하지 않는다 - 매매 API는 지번이 완전 비공개가 아니라
 *   "1**"처럼 마스킹돼서 오므로(전세 API와 다름), toSample()에서 이미 null로 정규화된 표본이
 *   자연스럽게 지오코딩 후보에서 빠져 표본부족으로 이어진다.
 * - differenceRate가 없다 - 매물 자체 가격과 비교하는 게 아니라 매매 기준가 자체가 필요하다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MarketSaleComparisonService {

    private final KakaoRegionCodeClient kakaoRegionCodeClient;
    private final KakaoAddressClient kakaoAddressClient;
    private final MolitTradeClient molitTradeClient;
    private final MarketComparisonProperties properties;

    private final Map<String, AddressResolutionResult> geocodeCache = new ConcurrentHashMap<>();

    public MarketSaleComparisonResponse compare(Property property) {
        PropertyAddress address = property.getAddress();
        if (address == null || address.getLatitude() == null || address.getLongitude() == null) {
            return MarketSaleComparisonResponse.unavailable(
                    MarketComparisonUnavailableReason.ADDRESS_INFO_MISSING, "매물 좌표 정보가 없어 매매 시세 조회가 어려워요.");
        }

        RegionCodeResult regionCode = kakaoRegionCodeClient.resolve(address.getLatitude(), address.getLongitude());
        if (!regionCode.isResolved()) {
            return MarketSaleComparisonResponse.unavailable(
                    MarketComparisonUnavailableReason.ADDRESS_INFO_MISSING, "지역 정보를 확인하지 못해 매매 시세 조회가 어려워요.");
        }

        String sggPrefix = extractSggPrefix(regionCode.getRegionName());

        List<TradeTransactionSample> candidates = fetchRecentTransactions(property.getPropertyType(), regionCode.getLawdCd());

        List<TradeTransactionSample> areaFiltered = candidates.stream()
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
                return MarketSaleComparisonResponse.unavailable(
                        MarketComparisonUnavailableReason.INSUFFICIENT_SAMPLE, "인근 매매 실거래 데이터가 부족해 시세 조회가 어려워요.");
            }
        }

        List<Long> dealAmounts = used.stream()
                .map(g -> g.sample().getDealAmountWon())
                .sorted()
                .toList();
        long referencePrice = median(dealAmounts);
        LocalDate latestDealDate = used.stream()
                .map(g -> g.sample().getDealDate())
                .max(Comparator.naturalOrder())
                .orElse(null);

        return MarketSaleComparisonResponse.available(
                referencePrice,
                used.size(),
                latestDealDate != null ? latestDealDate.toString() : null,
                radiusUsed
        );
    }

    private List<TradeTransactionSample> fetchRecentTransactions(PropertyType propertyType, String lawdCd) {
        List<TradeTransactionSample> result = new ArrayList<>();
        YearMonth current = YearMonth.now();
        for (int i = 0; i < properties.lookbackMonths(); i++) {
            result.addAll(molitTradeClient.fetch(propertyType, lawdCd, current.minusMonths(i)));
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

    private List<GeocodedSample> geocodeAll(List<TradeTransactionSample> samples, String sggPrefix) {
        List<GeocodedSample> result = new ArrayList<>();
        int failedCount = 0;
        for (TradeTransactionSample sample : samples) {
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
                log.debug("매매 실거래 지오코딩 실패 - 조합주소: {}", fullAddress);
            }
        }
        if (failedCount > 0) {
            log.info("매매 실거래 지오코딩 실패 {}건 (전체 후보 {}건 중) - 표본에서 제외됨", failedCount, samples.size());
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

    private record GeocodedSample(TradeTransactionSample sample, double latitude, double longitude) {
    }
}
