package com.algogyeyak.property.client;

/**
 * 좌표(위경도) -> 법정동코드 변환. Kakao Local API의 좌표to행정구역(coord2regioncode) 오퍼레이션을 사용한다.
 * 국토부 실거래가 API가 LAWD_CD(법정동코드 5자리)로만 조회 가능하기 때문에, 매물 좌표로부터
 * 이 코드를 얻어오는 용도로만 쓰인다 (marketdata 도메인에서 사용).
 */
public interface KakaoRegionCodeClient {

    RegionCodeResult resolve(double latitude, double longitude);
}
