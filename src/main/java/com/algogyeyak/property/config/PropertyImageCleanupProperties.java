package com.algogyeyak.property.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 매물 이미지 고아 객체 정리 배치(PropertyImageOrphanCleanupJob) 정책값.
 *
 * 매물 등록/수정 폼에서 사진을 고르면 즉시 presigned S3 업로드 -> confirm까지 끝나버리는데, 이
 * confirm은 실제 매물 저장(폼 제출)과 시점이 분리돼 있다. 사용자가 사진만 올리고 폼을 끝까지
 * 제출하지 않고 이탈하면 그 이미지는 S3PresignService.PENDING_UPLOAD_TAG도 이미 지워진 상태로(확정
 * 처리됐으므로) 버킷 Lifecycle 규칙의 대상에서도 벗어나, 이 배치가 없으면 영구 고아로 남는다.
 *
 * MarketComparisonProperties와 동일한 이유로 @Component를 붙이지 않는다 - 컴포넌트 스캔이 이
 * record를 일반 빈으로 취급해 생성자의 int 파라미터를 빈 자동주입 대상으로 찾다가 실패한다.
 * AlgogyeyakApplication의 @ConfigurationPropertiesScan이 대신 등록해준다.
 */
@ConfigurationProperties(prefix = "property.image-cleanup")
public record PropertyImageCleanupProperties(
        // confirm된 지 이 시간(시간 단위) 안에는 매물에 아직 안 붙어있어도 삭제하지 않는다 - 사용자가
        // 사진만 올려두고 나머지 등록 폼을 천천히 채우는 중일 수 있어서다.
        int gracePeriodHours
) {
}
