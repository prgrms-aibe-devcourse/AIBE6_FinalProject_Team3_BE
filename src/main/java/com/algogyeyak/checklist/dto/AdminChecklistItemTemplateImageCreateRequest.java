package com.algogyeyak.checklist.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * displayOrder는 요청에서 받지 않는다 - 서비스가 해당 문항의 기존 이미지 중 가장 큰 표시순서
 * 다음 값으로 자동 배정한다(신규 이미지는 항상 맨 뒤에 추가됨).
 */
public record AdminChecklistItemTemplateImageCreateRequest(
        // image_url 컬럼은 varchar(255)(ChecklistItemTemplateImage 참고) - 이 상한이 없으면 DB
        // 제약 위반(DataIntegrityViolationException)이 AdminChecklistTemplateService.addImage()의
        // catch 블록에서 "다른 관리자가 방금 이미지를 추가함"(동시성 충돌)으로 오분류돼, 실제로는
        // 값이 너무 길다는 게 원인인데 엉뚱한 안내로 재시도를 유도하게 된다.
        @NotBlank @Size(max = 255) String imageUrl
) {
}
