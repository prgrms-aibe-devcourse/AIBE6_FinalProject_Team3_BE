package com.algogyeyak.riskanalysis.client;

import com.algogyeyak.marketdata.dto.MarketSaleComparisonResponse;
import com.algogyeyak.marketdata.service.MarketSaleComparisonService;
import com.algogyeyak.property.repository.PropertyRepository;
import com.algogyeyak.riskanalysis.dto.MarketSalePrice;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

/**
 * market-data의 MarketSaleComparisonService를 risk-analysis(DepositSafetyCheckService)가
 * 기대하는 형태로 변환하는 어댑터. MarketDataClientImpl(전세 시세비교 어댑터)과 같은 패턴이다.
 * DepositSafetyCheckReason이 "판정불가" 사유를 ESTIMATED_PRICE_MISSING 하나로만 다루므로
 * (실거래가 부족/조회 실패를 세분화하지 않음), AVAILABLE이 아니면 전부 Optional.empty()로 뭉뚱그린다.
 */
@Component
@RequiredArgsConstructor
public class MarketSaleDataClientImpl implements MarketSaleDataClient {

    private final PropertyRepository propertyRepository;
    private final MarketSaleComparisonService marketSaleComparisonService;

    @Override
    public Optional<MarketSalePrice> getSalePrice(Long propertyId) {
        return propertyRepository.findById(propertyId)
                .map(marketSaleComparisonService::compare)
                .flatMap(this::toMarketSalePrice);
    }

    private Optional<MarketSalePrice> toMarketSalePrice(MarketSaleComparisonResponse response) {
        if (!"AVAILABLE".equals(response.status())) {
            return Optional.empty();
        }
        return Optional.of(new MarketSalePrice(
                BigDecimal.valueOf(response.referencePrice()),
                response.referenceDate() != null ? LocalDate.parse(response.referenceDate()) : null
        ));
    }
}
