package com.algogyeyak.marketdata.client;

import com.algogyeyak.property.entity.PropertyType;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;

/**
 * 국토부 실거래가 API 원본 응답을 정규화한 내부 표현.
 * 매물유형(오피스텔/연립다세대)마다 응답 필드가 조금씩 달라서(예: 단독/다가구는 지번 비공개)
 * 서비스 로직에서는 이 표준화된 형태만 다루도록 한다.
 */
@Getter
@Builder
public class RentTransactionSample {

    private final PropertyType propertyType;
    private final String buildingName;    // 오피스텔명/연립다세대명 (있는 경우만)
    private final String jibunAddress;    // 시군구+법정동+지번 조합 (지오코딩 대상, 단독/다가구는 null)
    private final String legalDongCode;   // sggCd
    private final String legalDongName;   // umdNm
    private final LocalDate dealDate;
    private final long depositWon;        // 보증금(전세금) - 원 단위로 환산됨
    private final long monthlyRentWon;    // 월세 - 원 단위로 환산됨 (0이면 전세)
    private final Double areaSqm;         // 전용면적(오피스텔/연립다세대) 또는 연면적(단독/다가구)

    public boolean isJeonse() {
        return monthlyRentWon == 0L;
    }
}
