package com.algogyeyak.marketdata.client;

import com.algogyeyak.property.entity.PropertyType;

import java.time.YearMonth;
import java.util.List;

/**
 * 국토부 매매 실거래가 공개 API 클라이언트.
 * 매물유형별로 서로 다른 API 상품(오피스텔/연립다세대/단독다가구)을 호출해 표준화된 형태로 반환한다.
 */
public interface MolitTradeClient {

    List<TradeTransactionSample> fetch(PropertyType propertyType, String lawdCd, YearMonth dealYm);
}
