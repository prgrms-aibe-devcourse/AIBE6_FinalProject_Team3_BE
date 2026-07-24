package com.algogyeyak.property.dto;

import com.algogyeyak.property.entity.Property;
import com.algogyeyak.property.entity.PropertyAddress;
import com.algogyeyak.property.entity.PropertyImage;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 매물 상세조회(GET /properties/{id}) 응답 DTO.
 * 목록(PropertyListResponse)과 달리 설명/이미지/전체 주소/시세비교까지 포함한다.
 */
public record PropertyDetailResponse(
        Long propertyId,
        String propertyType,
        String transactionType,
        Long deposit,
        Long monthlyRent,
        Double area,
        String description,
        AddressResponse address,
        List<String> imageUrls,
        MarketComparisonResponse marketComparison,
        String status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static PropertyDetailResponse from(Property property) {
        return new PropertyDetailResponse(
                property.getId(),
                property.getPropertyType().name(),
                property.getTransactionType().name(),
                property.getDeposit(),
                property.getMonthlyRent(),
                property.getArea(),
                property.getDescription(),
                AddressResponse.from(property.getAddress()),
                property.getImages().stream().map(PropertyImage::getImageUrl).toList(),
                MarketComparisonResponse.unavailable(),
                property.getStatus().name(),
                property.getCreatedAt(),
                property.getUpdatedAt()
        );
    }

    public record AddressResponse(
            String roadAddress,
            String jibunAddress,
            Double latitude,
            Double longitude
    ) {
        public static AddressResponse from(PropertyAddress address) {
            if (address == null) {
                return null;
            }
            return new AddressResponse(
                    address.getRoadAddress(),
                    address.getJibunAddress(),
                    address.getLatitude(),
                    address.getLongitude()
            );
        }
    }

    /**
     * 국토부 실거래가 연동 전까지는 등록 응답과 동일하게 항상 UNAVAILABLE.
     */
    public record MarketComparisonResponse(
            String status,
            Long referencePrice,
            Double differenceRate,
            Integer sampleCount,
            String referenceDate
    ) {
        public static MarketComparisonResponse unavailable() {
            return new MarketComparisonResponse("UNAVAILABLE", null, null, null, null);
        }
    }
}
