package com.algogyeyak.riskanalysis.client;

import com.algogyeyak.marketdata.dto.MarketComparisonResponse;
import com.algogyeyak.marketdata.dto.MarketComparisonUnavailableReason;
import com.algogyeyak.marketdata.service.MarketComparisonService;
import com.algogyeyak.property.entity.Property;
import com.algogyeyak.property.repository.PropertyRepository;
import com.algogyeyak.riskanalysis.dto.MarketComparison;
import com.algogyeyak.riskanalysis.enums.MarketComparisonStatus;
import com.algogyeyak.riskanalysis.enums.MarketUnavailableReason;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

/**
 * market-data의 MarketComparisonService를 risk-analysis가 기대하는 형태(MarketComparison,
 * SUCCESS/UNDETERMINABLE/FAILED 3단계)로 변환하는 어댑터. market-data는 아직 "판정불가"와
 * "API 실패"를 구분해 넘겨주지 않으므로(MolitRentClientImpl이 API 실패도 빈 리스트로 삼킴),
 * 이 어댑터는 AVAILABLE이 아니면 전부 UNDETERMINABLE로만 매핑한다 - 진짜 FAILED 판정은
 * market-data 쪽 선행 작업이 끝난 뒤 다룰 과제로 남겨둔다.
 */
@Component
@RequiredArgsConstructor
public class MarketDataClientImpl implements MarketDataClient {

    private final PropertyRepository propertyRepository;
    private final MarketComparisonService marketComparisonService;

    @Override
    public Optional<MarketComparison> getComparison(Long propertyId) {
        return propertyRepository.findById(propertyId)
                .map(property -> toMarketComparison(property, marketComparisonService.compare(property)));
    }

    private MarketComparison toMarketComparison(Property property, MarketComparisonResponse response) {
        if (!"AVAILABLE".equals(response.status())) {
            return new MarketComparison(
                    property.getId(), null, null, null, 0, null, null,
                    mapReason(response.reason()), MarketComparisonStatus.UNDETERMINABLE
            );
        }

        return new MarketComparison(
                property.getId(),
                BigDecimal.valueOf(response.referencePrice()),
                BigDecimal.valueOf(property.getDeposit()),
                BigDecimal.valueOf(response.differenceRate()),
                response.sampleCount(),
                LocalDate.parse(response.referenceDate()),
                response.radiusMeters(),
                null,
                MarketComparisonStatus.SUCCESS
        );
    }

    private MarketUnavailableReason mapReason(MarketComparisonUnavailableReason reason) {
        if (reason == null) {
            return null;
        }
        return switch (reason) {
            // (2026-08-14) 예전엔 이 둘을 PROPERTY_TYPE_UNSUPPORTED 하나로 뭉뚱그려 매핑했었다 -
            // risk-analysis-design.md 전수조사 결과 버그 2번 참고.
            case TRANSACTION_TYPE_UNSUPPORTED -> MarketUnavailableReason.TRANSACTION_TYPE_UNSUPPORTED;
            case PROPERTY_TYPE_UNSUPPORTED -> MarketUnavailableReason.PROPERTY_TYPE_UNSUPPORTED;
            case ADDRESS_INFO_MISSING -> MarketUnavailableReason.ADDRESS_INFO_MISSING;
            case INSUFFICIENT_SAMPLE -> MarketUnavailableReason.INSUFFICIENT_SAMPLE;
        };
    }
}
