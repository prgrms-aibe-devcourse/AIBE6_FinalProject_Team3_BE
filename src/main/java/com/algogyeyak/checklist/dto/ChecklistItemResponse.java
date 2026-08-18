package com.algogyeyak.checklist.dto;

import com.algogyeyak.checklist.entity.ChecklistCategory;
import com.algogyeyak.checklist.entity.ChecklistImportance;
import com.algogyeyak.checklist.entity.ChecklistItem;
import com.algogyeyak.checklist.entity.ChecklistItemTemplateImage;
import com.algogyeyak.checklist.entity.ChecklistItemType;
import java.util.Arrays;
import java.util.List;

public record ChecklistItemResponse(
        Long id,
        ChecklistCategory category,
        String content,
        String guideText,
        String helperText,
        ChecklistImportance importance,
        ChecklistItemType itemType,
        List<String> options,
        boolean checked,
        boolean issueFound,
        String value,
        String userNote,
        List<String> images
) {
    public static ChecklistItemResponse from(ChecklistItem item) {
        return new ChecklistItemResponse(
                item.getId(),
                item.getCategory(),
                item.getContent(),
                item.getGuideText(),
                item.getHelperText(),
                item.getImportance(),
                item.getItemType(),
                item.getOptions() == null ? List.of() : Arrays.stream(item.getOptions().split(",")).map(String::trim).toList(),
                item.isChecked(),
                item.hasIssue(),
                item.getValue(),
                item.getUserNote(),
                item.getTemplate() == null
                        ? List.of()
                        : item.getTemplate().getImages().stream().map(ChecklistItemTemplateImage::getImageUrl).toList()
        );
    }
}
