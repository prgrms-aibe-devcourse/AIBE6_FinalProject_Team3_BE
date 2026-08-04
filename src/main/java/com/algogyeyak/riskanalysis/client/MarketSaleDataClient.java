package com.algogyeyak.riskanalysis.client;

import com.algogyeyak.riskanalysis.dto.MarketSalePrice;

import java.util.Optional;

public interface MarketSaleDataClient {
    Optional<MarketSalePrice> getSalePrice(Long propertyId);
}
