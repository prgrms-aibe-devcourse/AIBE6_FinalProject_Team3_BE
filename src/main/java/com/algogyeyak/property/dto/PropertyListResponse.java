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
        LocalDateTime createdAt
) {
    public static PropertyListResponse from(Property property) {
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
                property.getCreatedAt()
        );
    }
}
