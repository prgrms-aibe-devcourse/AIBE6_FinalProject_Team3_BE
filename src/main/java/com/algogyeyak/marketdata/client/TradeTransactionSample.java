package com.algogyeyak.marketdata.client;

import com.algogyeyak.property.entity.PropertyType;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;

/**
 * 국토부 매매 실거래가 API 원본 응답을 정규화한 내부 표현.
 * {@link RentTransactionSample}과 동일한 이유로 매물유형별 응답 필드 차이를 흡수한다.
 * 매매는 전세/월세 구분이 없어 금액 필드가 dealAmountWon 하나뿐이다.
 */
@Getter
@Builder
public class TradeTransactionSample {

    private final PropertyType propertyType;
    private final String buildingName;    // 오피스텔명/연립다세대명 (있는 경우만)
    private final String jibunAddress;    // 시군구+법정동+지번 조합 (지오코딩 대상, 단독/다가구는 마스킹됨)
    private final String legalDongCode;   // sggCd
    private final String legalDongName;   // umdNm
    private final LocalDate dealDate;
    private final long dealAmountWon;     // 매매금액 - 원 단위로 환산됨
    private final Double areaSqm;         // 전용면적(오피스텔/연립다세대) 또는 연면적(단독/다가구)
}
