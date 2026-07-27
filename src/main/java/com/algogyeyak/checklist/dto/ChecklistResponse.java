package com.algogyeyak.checklist.dto;

import com.algogyeyak.checklist.entity.Checklist;
import com.algogyeyak.checklist.entity.ChecklistStatus;

import java.util.List;

public record ChecklistResponse(
        Long id,
        Long propertyId,
        int templateVersion,
        ChecklistStatus status,
        List<ChecklistItemResponse> items
) {
    public static ChecklistResponse from(Checklist checklist) {
        return new ChecklistResponse(
                checklist.getId(),
                checklist.getProperty().getId(),
                checklist.getTemplateVersion(),
                checklist.getStatus(),
                checklist.getItems().stream().map(ChecklistItemResponse::from).toList()
        );
    }
}
