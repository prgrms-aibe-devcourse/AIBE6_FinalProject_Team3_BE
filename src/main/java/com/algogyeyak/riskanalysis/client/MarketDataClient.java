package com.algogyeyak.riskanalysis.client;

import com.algogyeyak.riskanalysis.dto.MarketComparison;

import java.util.Optional;

public interface MarketDataClient {
    Optional<MarketComparison> getComparison(Long propertyId);
}

