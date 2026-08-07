package com.algogyeyak.property.dto;

public record PropertyImageUploadUrlResponse(
        String uploadUrl,
        String key,
        // 클라이언트가 S3에 직접 PUT할 때 x-amz-tagging 헤더에 그대로 실어 보내야 하는 값
        // (S3PresignService.PENDING_UPLOAD_TAG). presign 서명에 이 태그가 포함돼 있어서, 값이
        // 다르면 S3가 서명 불일치로 403을 반환한다.
        String tagging
) {
}
