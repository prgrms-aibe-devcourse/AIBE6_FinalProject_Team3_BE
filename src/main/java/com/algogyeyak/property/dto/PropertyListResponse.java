package com.algogyeyak.property.dto;

import com.algogyeyak.property.entity.Property;
import java.time.LocalDateTime;

/**
 * 매물 목록조회(GET /properties) 응답용 요약 DTO.
 * 상세조회(PropertyRegisterResponse류)와 달리 목록에서는 주소 전체/이미지 등은 생략하고
 * 카드형 UI에 필요한 최소 정보만 내려준다.
 */
public record PropertyListResponse(
        Long propertyId,
        String propertyType,
        String transactionType,
        Long deposit,
        Long monthlyRent,
        Double area,
        String roadAddress,
        String jibunAddress,
        String status,
        LocalDateTime createdAt,
        Integer checklistProgress
) {
    /**
     * checklistProgress는 체크리스트를 아예 시작하지 않았으면 null(체크리스트 자체가 없어서 분모가
     * 없음), 시작했으면 0~100 사이 정수(체크된 문항 수 / 전체 문항 수 * 100, 반올림)로 내려간다.
     */
    public static PropertyListResponse from(Property property, Integer checklistProgress) {
        var address = property.getAddress();
        return new PropertyListResponse(
                property.getId(),
                property.getPropertyType().name(),
                property.getTransactionType().name(),
                property.getDeposit(),
                property.getMonthlyRent(),
                property.getArea(),
                address != null ? address.getRoadAddress() : null,
                address != null ? address.getJibunAddress() : null,
                property.getStatus().name(),
                property.getCreatedAt(),
                checklistProgress
        );
    }
}
