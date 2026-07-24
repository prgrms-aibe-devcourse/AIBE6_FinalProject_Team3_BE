package com.algogyeyak.property.dto;

import com.algogyeyak.property.entity.Property;
import com.algogyeyak.property.entity.PropertyAddress;

public record PropertyRegisterResponse(
        Long propertyId,
        String status,
        AddressResponse address,
        MarketComparisonResponse marketComparison,
        String notice
) {

    public static PropertyRegisterResponse of(Property property, String notice) {
        PropertyAddress propertyAddress = property.getAddress();
        return new PropertyRegisterResponse(
                property.getId(),
                property.getStatus().name(),
                AddressResponse.from(propertyAddress),
                MarketComparisonResponse.unavailable(),
                notice
        );
    }

    public record AddressResponse(
            String roadAddress,
            String jibunAddress,
            Double latitude,
            Double longitude
    ) {
        public static AddressResponse from(PropertyAddress address) {
            return new AddressResponse(
                    address.getRoadAddress(),
                    address.getJibunAddress(),
                    address.getLatitude(),
                    address.getLongitude()
            );
        }
    }

    /**
     * 국토부 실거래가 연동은 아직 구현 전이라 항상 UNAVAILABLE로 반환한다.
     * (연동 완료되면 이 부분을 실제 시세 비교 로직으로 교체)
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
