package com.algogyeyak.global.s3.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class PresignedUploadRequest {

    @NotBlank
    private String fileExtension;

    @NotBlank
    private String contentType;

    // 실제 업로드 바이트 수 - S3PresignService가 presigned URL의 서명 대상 Content-Length로 그대로
    // 사용하므로, 여기 적힌 값과 실제 업로드 크기가 다르면 S3가 업로드 자체를 거부한다. 상한(프로필 5MB /
    // 매물·계약서 10MB)은 용도별로 달라 여기선 고정할 수 없으므로 S3PresignService가 S3ImagePurpose
    // 기준으로 검증한다 - 여기선 값 자체의 유효성(양수)만 확인한다.
    @NotNull
    @Positive
    private Long fileSize;
}
