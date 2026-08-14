package com.algogyeyak.checklist.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * displayOrder는 요청에서 받지 않는다 - 서비스가 해당 문항의 기존 이미지 중 가장 큰 표시순서
 * 다음 값으로 자동 배정한다(신규 이미지는 항상 맨 뒤에 추가됨).
 */
public record AdminChecklistItemTemplateImageCreateRequest(
        @NotBlank String imageUrl
) {
}
