package com.algogyeyak.property.dto;

import com.algogyeyak.marketdata.dto.MarketComparisonResponse;
import com.algogyeyak.property.entity.Property;
import java.time.LocalDateTime;

/**
 * 매물 목록조회(GET /properties) 응답용 요약 DTO.
 * 상세조회(PropertyRegisterResponse류)와 달리 목록에서는 주소 전체/이미지 등은 생략하고
 * 카드형 UI에 필요한 최소 정보만 내려준다.
 */
public record PropertyListResponse(
        Long propertyId,
        String title,
        String propertyType,
        String transactionType,
        Long deposit,
        Long monthlyRent,
        Double area,
        String roadAddress,
        String jibunAddress,
        String status,
        LocalDateTime createdAt,
        Integer checklistProgress,
        MarketComparisonResponse marketComparison
) {
    /**
     * checklistProgress는 체크리스트를 아예 시작하지 않았으면 null(체크리스트 자체가 없어서 분모가
     * 없음), 시작했으면 0~100 사이 정수(체크된 문항 수 / 전체 문항 수 * 100, 반올림)로 내려간다.
     * marketComparison은 상세조회(PropertyDetailResponse)와 같은 MarketComparisonService.compare()
     * 결과를 그대로 재사용한다 - property.id 기준 Redis 캐싱이 이미 돼있어(TTL 정책값), 상세조회를
     * 한 번이라도 거친 매물은 목록조회에서도 국토부/카카오 API를 다시 호출하지 않는다. 이 API가
     * "본인이 등록한 매물"만 페이지네이션하는 개인용 목록이라(마켓플레이스식 전체조회가 아님) 페이지당
     * 매물 수가 많지 않아, 캐시 미스가 나는 매물이 섞여도 목록조회 하나가 심각하게 느려지진 않는다.
     */
    public static PropertyListResponse from(
            Property property, Integer checklistProgress, MarketComparisonResponse marketComparison
    ) {
        var address = property.getAddress();
        return new PropertyListResponse(
                property.getId(),
                property.getTitle(),
                property.getPropertyType().name(),
                property.getTransactionType().name(),
                property.getDeposit(),
                property.getMonthlyRent(),
                property.getArea(),
                address != null ? address.getRoadAddress() : null,
                address != null ? address.getJibunAddress() : null,
                property.getStatus().name(),
                property.getCreatedAt(),
                checklistProgress,
                marketComparison
        );
    }
}
