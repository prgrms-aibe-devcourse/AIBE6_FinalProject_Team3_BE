package com.algogyeyak.checklist.dto;

import com.algogyeyak.checklist.entity.ChecklistItemTemplateImage;

public record AdminChecklistItemTemplateImageResponse(
        Long id,
        String imageUrl,
        int displayOrder
) {
    public static AdminChecklistItemTemplateImageResponse from(ChecklistItemTemplateImage image) {
        return new AdminChecklistItemTemplateImageResponse(
                image.getId(),
                image.getImageUrl(),
                image.getDisplayOrder()
        );
    }
}
