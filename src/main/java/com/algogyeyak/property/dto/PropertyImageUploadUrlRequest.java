package com.algogyeyak.property.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 매물 이미지 업로드용 presigned URL 발급 요청. 신규 등록 시점엔 아직 propertyId가 없으므로
 * S3 키는 propertyId가 아니라 로그인한 userId 기준 네임스페이스로 생성한다
 * (S3KeyGenerator.propertyImageKey를 그대로 재사용 - 인자 이름은 propertyId지만 owner id로 써도 무방).
 */
public record PropertyImageUploadUrlRequest(
        @NotBlank(message = "파일 확장자는 필수입니다.")
        String fileExtension,

        @NotBlank(message = "컨텐츠 타입은 필수입니다.")
        String contentType,

        @NotNull(message = "파일 크기는 필수입니다.")
        @Positive(message = "파일 크기는 0보다 커야 합니다.")
        Long fileSize
) {
}
