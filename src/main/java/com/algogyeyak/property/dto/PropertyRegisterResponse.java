package com.algogyeyak.property.dto;

import com.algogyeyak.marketdata.dto.MarketComparisonResponse;
import com.algogyeyak.property.entity.Property;
import com.algogyeyak.property.entity.PropertyAddress;

public record PropertyRegisterResponse(
        Long propertyId,
        String status,
        AddressResponse address,
        MarketComparisonResponse marketComparison,
        String notice
) {

    public static PropertyRegisterResponse of(Property property, String notice, MarketComparisonResponse marketComparison) {
        PropertyAddress propertyAddress = property.getAddress();
        return new PropertyRegisterResponse(
                property.getId(),
                property.getStatus().name(),
                AddressResponse.from(propertyAddress),
                marketComparison,
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
}
