package com.algogyeyak.riskanalysis.client;

import com.algogyeyak.riskanalysis.dto.MarketComparison;
import com.algogyeyak.riskanalysis.enums.MarketComparisonStatus;
import com.algogyeyak.riskanalysis.enums.MarketUnavailableReason;
import org.springframework.stereotype.Component;

import java.util.Optional;

// TODO: market-data 도메인에서 실제 MarketDataClient 구현체가 나오면 이 클래스는 삭제한다.
@Component
public class TemporaryMarketDataClient implements MarketDataClient {

    @Override
    public Optional<MarketComparison> getComparison(Long propertyId) {
        return Optional.of(new MarketComparison(
                propertyId,
                null,
                null,
                null,
                0,
                null,
                null,
                MarketUnavailableReason.EXTERNAL_API_FAILURE,
                MarketComparisonStatus.UNDETERMINABLE
        ));
    }
}