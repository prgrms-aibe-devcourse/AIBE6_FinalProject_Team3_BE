package com.algogyeyak.property.client;

/**
 * 주소 문자열을 Kakao Local API로 정규화(도로명/지번) + 좌표 변환하는 클라이언트.
 * 매물 등록/수정 시 동기 호출로 사용됨.
 */
public interface KakaoAddressClient {

    /**
     * @param address 사용자가 입력/검색한 주소 원문
     * @return 정규화 결과. 검색 결과가 없거나 API 호출 실패 시 resolved=false
     */
    AddressResolutionResult resolve(String address);
}
