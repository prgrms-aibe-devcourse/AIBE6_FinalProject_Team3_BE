package com.algogyeyak.global.s3.util;

import java.util.Set;

// 업로드 대상(프로필/매물/계약서 이미지)별 확장자·컨텐츠타입 화이트리스트, 최대 용량, 공개 여부를
// 한 곳에 모은다. S3KeyGenerator(확장자 검증)와 S3PresignService(컨텐츠타입/용량/조회 URL 방식)가
// 같은 정책을 공유해야 하므로, 여러 클래스에 각각 상수를 두면 나중에 한쪽만 바뀌어 어긋나는 걸 막기
// 위해 여기로 합쳤다.
public enum S3ImagePurpose {

    // 프로필/매물 이미지는 버킷 정책으로 public read를 허용하기로 결정(2026-08-03) - 조회 시
    // presigned URL이 아니라 영구 고정 URL을 그대로 써도 된다.
    PROFILE(
            Set.of("jpg", "jpeg", "png"),
            Set.of("image/jpeg", "image/png"),
            5 * 1024 * 1024,
            true
    ),
    // PropertyService.ALLOWED_IMAGE_EXTENSIONS와 동일한 목록을 유지한다 - 어긋나면 이미지 URL 등록은
    // 통과하는데 실제 S3 업로드(presigned 발급)만 막히는 상황이 생긴다.
    PROPERTY(
            Set.of("jpg", "jpeg", "png", "webp", "gif"),
            Set.of("image/jpeg", "image/png", "image/webp", "image/gif"),
            10 * 1024 * 1024,
            true
    ),
    // ContractAnalysisOcrService/InputService가 Clova OCR 지원 포맷(jpg/png)만 받는 것과 맞춘다.
    // 계약서 이미지는 개인정보 정책상 private 유지 - 조회는 항상 presigned URL로만 가능하다.
    CONTRACT(
            Set.of("jpg", "jpeg", "png"),
            Set.of("image/jpeg", "image/png"),
            10 * 1024 * 1024,
            false
    );

    private final Set<String> allowedExtensions;
    private final Set<String> allowedContentTypes;
    private final long maxSizeBytes;
    private final boolean isPublic;

    S3ImagePurpose(Set<String> allowedExtensions, Set<String> allowedContentTypes, long maxSizeBytes, boolean isPublic) {
        this.allowedExtensions = allowedExtensions;
        this.allowedContentTypes = allowedContentTypes;
        this.maxSizeBytes = maxSizeBytes;
        this.isPublic = isPublic;
    }

    public Set<String> allowedExtensions() {
        return allowedExtensions;
    }

    public Set<String> allowedContentTypes() {
        return allowedContentTypes;
    }

    public long maxSizeBytes() {
        return maxSizeBytes;
    }

    public boolean isPublic() {
        return isPublic;
    }
}
