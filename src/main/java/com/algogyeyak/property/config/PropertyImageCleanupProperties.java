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
        int gracePeriodHours,
        // 삭제 후보가 전체 S3 객체 대비 이 비율(0~1)을 넘으면 이번 실행을 통째로 건너뛰고 경고만
        // 남긴다. DB 조회가 어떤 이유로든(연결 실패, 잘못된 프로필/환경, 마이그레이션 중 등)
        // 비정상적으로 비거나 적은 결과를 반환하면 이 배치가 "참조된 이미지가 거의 없다"고 오판해
        // 정상 운영 중인 이미지까지 통째로 지워버릴 수 있다는 게 로컬 테스트 중 실제로 재현됐다
        // (2026-08-18, 실제 버킷의 객체 65건을 전부 고아로 오판해 삭제함). 이 서킷브레이커가 그
        // 최악의 시나리오(전체/대량 삭제)를 막아준다 - 소량의 정상적인 고아 정리는 그대로 통과된다.
        double maxDeleteRatio
) {
}
