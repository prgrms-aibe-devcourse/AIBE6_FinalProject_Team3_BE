package com.algogyeyak.property.client;

import lombok.Builder;
import lombok.Getter;

/**
 * 좌표 -> 법정동코드 변환 결과.
 * 국토부 실거래가 API(LAWD_CD)는 법정동코드 10자리 중 앞 5자리(시군구코드)를 요구한다.
 */
@Getter
@Builder
public class RegionCodeResult {

    private final boolean resolved;
    private final String legalDongCode;   // 법정동코드 10자리 (Kakao b_code)
    private final String lawdCd;          // 앞 5자리 - 국토부 API의 LAWD_CD 파라미터
    private final String regionName;      // 예: 서울특별시 종로구 청운동

    public static RegionCodeResult unresolved() {
        return RegionCodeResult.builder().resolved(false).build();
    }
}
