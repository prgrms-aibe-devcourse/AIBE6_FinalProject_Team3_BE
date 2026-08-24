package com.algogyeyak.user.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 프로필 이미지 고아 객체 정리 배치(ProfileImageOrphanCleanupJob) 정책값.
 *
 * {@code S3PresignService.deleteReplacedObject}는 이전 프로필 이미지를 교체/삭제할 때
 * best-effort로 즉시 삭제를 시도하고, 그 직전에 안전망으로 PENDING_UPLOAD_TAG를 다시 걸어
 * Lifecycle 규칙이 나중에라도 정리할 수 있게 해둔다. 하지만 태깅 자체와 즉시 삭제가 둘 다
 * 실패하면(권한/네트워크 등) 태그도 없이 영구 고아로 남을 수 있다 - 이 배치는 태그/Lifecycle과
 * 무관하게 S3 실제 객체와 DB 참조를 직접 대조해 그 이중 실패 케이스까지 정리한다.
 *
 * property.image-cleanup(PropertyImageCleanupProperties)과 동일한 이유로 @Component를 붙이지
 * 않는다 - 컴포넌트 스캔이 이 record를 일반 빈으로 취급해 생성자의 int/double 파라미터를 빈
 * 자동주입 대상으로 찾다가 실패한다. AlgogyeyakApplication의 @ConfigurationPropertiesScan이
 * 대신 등록해준다.
 */
@ConfigurationProperties(prefix = "user.profile-image-cleanup")
public record ProfileImageCleanupProperties(
        // confirm된 지 이 시간(시간 단위) 안에는 User.profileImageUrl에 아직 반영되지 않았어도
        // 삭제하지 않는다 - confirm과 User.profileImageUrl 갱신은 같은 트랜잭션이라 사실상 항상
        // 즉시 반영되지만, PropertyImageCleanupProperties와 동일한 여유를 그대로 둔다.
        int gracePeriodHours,
        // 삭제 후보가 전체 S3 객체 대비 이 비율(0~1)을 넘으면 이번 실행을 통째로 건너뛰고 경고만
        // 남긴다(서킷브레이커) - DB 조회가 비정상적으로 비어 "참조된 이미지가 거의 없다"고
        // 오판하면 정상 운영 중인 이미지까지 통째로 지워버릴 수 있다(PropertyImageOrphanCleanupJob에서
        // 2026-08-18 실제 재현된 사고와 동일한 위험).
        double maxDeleteRatio
) {
}
