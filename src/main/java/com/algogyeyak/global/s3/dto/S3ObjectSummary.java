package com.algogyeyak.global.s3.dto;

import java.time.Instant;

/**
 * S3PresignService.listObjects()가 돌려주는 최소 정보 - AWS SDK의 S3Object를 호출부(배치 등)에
 * 그대로 노출하지 않기 위한 경계용 DTO. 지금은 고아 이미지 정리 배치(PropertyImageOrphanCleanupJob)
 * 하나만 쓰지만, 다른 purpose(profile-images/ 등)에도 같은 정리 로직이 필요해지면 재사용할 수 있다.
 */
public record S3ObjectSummary(String key, Instant lastModified) {
}
