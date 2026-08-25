package com.algogyeyak.marketdata.dto;

/**
 * MarketTransactionSampleResponse가 대표 5건으로 추려질 때(MarketComparisonService.
 * pickRepresentativeSamples 참고), 이 표본이 왜 뽑혔는지 사용자에게 알려주기 위한 표시.
 * 최종 목록은 항상 최신 계약일순으로 정렬되기 때문에, 최고가/최저가로 뽑힌 표본이 목록 중간에
 * 섞여 있으면 "왜 이게 여기 있지?" 하고 혼란스러울 수 있다 - 이 필드로 FE가 배지를 붙여준다.
 * 표본이 5건 이하라 추릴 필요가 없었던 경우(전부 노출)에는 모든 표본이 null이다.
 */
public enum SamplePriceHighlight {
    HIGHEST, // 대표 5건 선정 기준으로 뽑힌 최고가 표본
    LOWEST   // 대표 5건 선정 기준으로 뽑힌 최저가 표본
}
