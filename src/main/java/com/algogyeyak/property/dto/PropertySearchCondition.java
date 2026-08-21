package com.algogyeyak.property.dto;

import com.algogyeyak.property.entity.PropertyType;
import com.algogyeyak.property.entity.TransactionType;

/**
 * 매물 목록 조회(GET /properties) 검색/필터 조건. 전부 선택값(null 허용)이며,
 * null인 조건은 필터링하지 않는다(전부 null이면 기존과 동일하게 본인 소유 전체 목록).
 *
 * region은 메인 검색창 하나로 입력받는 자유 텍스트 검색어다 - 도로명주소/지번주소(region 컬럼) 또는
 * 건물명(title 컬럼) 중 하나라도 부분일치(LIKE)하면 매칭된다(5차 멘토링 피드백 6-3, OR 조건).
 * 처음엔 region/title을 별도 입력란 + AND 조건으로 구현했었는데, 사용자가 상단 검색창 하나에 주소든
 * 건물명이든 입력하면 찾아지길 기대해서 단일 검색어의 OR 매칭으로 수정함(2026-08-21). 법정동코드 등
 * 정확 매칭 인프라가 아직 없어 두 컬럼 다 자유 텍스트 검색으로 처리한다(market-data-design.md 참고,
 * 별도 인프라 필요).
 */
public record PropertySearchCondition(
        String region,
        Double minArea,
        Double maxArea,
        TransactionType transactionType,
        PropertyType propertyType,
        Long minDeposit,
        Long maxDeposit,
        // 전세는 monthlyRent가 항상 null이라 이 조건은 사실상 월세 매물에만 의미가 있다 -
        // transactionType=JEONSE와 함께 넘어와도 에러는 아니고 그냥 결과가 0건이 될 뿐이다.
        Long minMonthlyRent,
        Long maxMonthlyRent,
        // true면 확인 필요 신호(checkSignalCount > 0)가 있는 매물만 필터링한다(#233). null/false면
        // 조건 자체를 무시(기존과 동일하게 전체 목록). risk-analysis가 유지하는 값이라 이 조건의
        // 실제 매물 id 목록 계산은 PropertyService가 PropertyRiskSummaryProvider를 통해 수행한다 -
        // 여기 hasSignal 자체는 그 계산을 트리거하는 사용자 의도만 표현한다.
        Boolean hasSignal
) {
    public static PropertySearchCondition empty() {
        return new PropertySearchCondition(null, null, null, null, null, null, null, null, null, null);
    }
}
