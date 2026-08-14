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
            "profile-images/",
            Set.of("jpg", "jpeg", "png"),
            Set.of("image/jpeg", "image/png"),
            5 * 1024 * 1024,
            true
    ),
    // PropertyService.ALLOWED_IMAGE_EXTENSIONS와 동일한 목록을 유지한다 - 어긋나면 이미지 URL 등록은
    // 통과하는데 실제 S3 업로드(presigned 발급)만 막히는 상황이 생긴다.
    PROPERTY(
            "property-images/",
            Set.of("jpg", "jpeg", "png", "webp", "gif"),
            Set.of("image/jpeg", "image/png", "image/webp", "image/gif"),
            10 * 1024 * 1024,
            true
    ),
    // ContractAnalysisOcrService/InputService가 Clova OCR 지원 포맷(jpg/png)만 받는 것과 맞춘다.
    // 계약서 이미지는 개인정보 정책상 private 유지 - 조회는 항상 presigned URL로만 가능하다.
    CONTRACT(
            "contract-images/",
            Set.of("jpg", "jpeg", "png"),
            Set.of("image/jpeg", "image/png"),
            10 * 1024 * 1024,
            false
    ),
    // 체크리스트 문항 예시 이미지(누수/콘센트/차단기 등). 지금은 관리자 업로드 화면 없이 콘솔에서
    // 직접 올린 파일을 시더가 key로만 참조하지만(ChecklistTemplateSeeder), presign 업로드 API를
    // 나중에 열어도 같은 purpose를 그대로 재사용할 수 있도록 PROFILE과 동일한 정책으로 맞춘다.
    CHECKLIST_TEMPLATE(
            "checklist-template-images/",
            Set.of("jpg", "jpeg", "png"),
            Set.of("image/jpeg", "image/png"),
            5 * 1024 * 1024,
            true
    );

    // key 네임스페이스 prefix도 이 enum에 모은다(2026-08-12) - S3PresignService가 confirm/download
    // 시점에 "이 key가 실제로 이 purpose 소유인지"를 공통으로 검증하는 데 쓴다. 이게 없으면 어떤
    // 컨트롤러가 소유권 검증을 깜빡했을 때 다른 도메인(예: 비공개 contract-images/)의 key를 이
    // purpose(예: PROPERTY, public)로 confirm/조회해 실제로는 비공개인 객체를 공개 URL로 노출시킬
    // 수 있다 - purpose-prefix 불일치만으로도 이런 교차 도메인 오용은 prefix 검증 하나로 막힌다.
    private final String prefix;
    private final Set<String> allowedExtensions;
    private final Set<String> allowedContentTypes;
    private final long maxSizeBytes;
    private final boolean isPublic;

    S3ImagePurpose(String prefix, Set<String> allowedExtensions, Set<String> allowedContentTypes, long maxSizeBytes, boolean isPublic) {
        this.prefix = prefix;
        this.allowedExtensions = allowedExtensions;
        this.allowedContentTypes = allowedContentTypes;
        this.maxSizeBytes = maxSizeBytes;
        this.isPublic = isPublic;
    }

    public String prefix() {
        return prefix;
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
