package com.algogyeyak.property.dto;

import com.algogyeyak.property.entity.RoomType;
import jakarta.validation.constraints.NotBlank;

/**
 * 매물 등록/수정 요청에 포함되는 이미지 한 장. imageUrl은 사전에 이미지 업로드 API
 * (POST /properties/images/upload-url → S3 PUT → POST /properties/images/confirm)를 거쳐 받은
 * 확정 URL이어야 한다. roomType은 선택값 - 라벨 없이 올릴 수도 있다.
 */
public record PropertyImageRequest(
        @NotBlank(message = "이미지 URL은 필수입니다.")
        String imageUrl,

        RoomType roomType
) {
}
