package com.algogyeyak.property.dto;

import com.algogyeyak.property.entity.PropertyImage;

public record PropertyImageResponse(
        String imageUrl,
        String roomType
) {
    public static PropertyImageResponse from(PropertyImage image) {
        return new PropertyImageResponse(
                image.getImageUrl(),
                image.getRoomType() != null ? image.getRoomType().name() : null
        );
    }
}
