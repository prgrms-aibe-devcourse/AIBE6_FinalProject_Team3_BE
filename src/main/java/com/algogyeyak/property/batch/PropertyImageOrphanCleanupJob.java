package com.algogyeyak.property.batch;

import com.algogyeyak.global.s3.dto.S3ObjectSummary;
import com.algogyeyak.global.s3.service.S3PresignService;
import com.algogyeyak.global.s3.util.S3ImagePurpose;
import com.algogyeyak.property.config.PropertyImageCleanupProperties;
import com.algogyeyak.property.repository.PropertyImageRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 매물 이미지 고아 객체 정리 배치.
 *
 * 매물 등록/수정 폼에서 사진을 고르면 즉시 presigned S3 업로드 -> confirm까지 끝나버리는데, 이
 * confirm 시점에 S3PresignService.PENDING_UPLOAD_TAG가 이미 지워진다(확정 처리됐기 때문). 그런데
 * 이 confirm은 실제 매물 저장(등록/수정 폼 제출)과 시점이 완전히 분리돼 있어서, 사용자가 사진만
 * 올리고 폼을 끝까지 제출하지 않고 이탈하면 그 이미지는 태그도 없고 어떤 매물에도 연결되지 않은 채
 * S3에 영구 고아로 남는다 - 버킷 Lifecycle 규칙은 PENDING_UPLOAD_TAG가 붙은 객체만 정리 대상으로
 * 보기 때문에 이 케이스를 잡지 못한다(#214).
 *
 * 이 배치는 S3 property-images/ prefix 전체와 DB(PropertyImageRepository)에 실제로 참조된
 * imageUrl 집합을 대조해서, DB에 없으면서(=어떤 매물에도 안 붙었으면서) 그레이스 기간
 * (PropertyImageCleanupProperties.gracePeriodHours) 이상 지난 객체만 삭제한다. 그레이스 기간을
 * 두는 이유는 사용자가 사진만 올려두고 나머지 등록 폼을 천천히 채우는 중일 수 있어서다 - confirm
 * 직후 아직 폼 제출 전인 이미지를 그 사이에 지워버리면 안 된다.
 *
 * 기존 등록/수정 로직(PropertyService)은 전혀 건드리지 않는다 - 완전히 독립된 배치라서 회귀
 * 위험이 없고, 도메인 경계(property가 S3PresignService를 직접 다루는 것)도 컨트롤러/이 배치에만
 * 국한된다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PropertyImageOrphanCleanupJob {

    private final S3PresignService s3PresignService;
    private final PropertyImageRepository propertyImageRepository;
    private final PropertyImageCleanupProperties properties;

    @Scheduled(cron = "${property.image-cleanup.cron}")
    public void cleanUp() {
        Set<String> referencedKeys = propertyImageRepository.findAllImageUrls().stream()
                .map(s3PresignService::extractOwnedKey)
                .flatMap(Optional::stream)
                .collect(Collectors.toSet());

        List<S3ObjectSummary> objects = s3PresignService.listObjects(S3ImagePurpose.PROPERTY);
        Instant cutoff = Instant.now().minus(properties.gracePeriodHours(), ChronoUnit.HOURS);

        int deletedCount = 0;
        for (S3ObjectSummary object : objects) {
            if (referencedKeys.contains(object.key())) {
                continue;
            }
            if (object.lastModified().isAfter(cutoff)) {
                // 그레이스 기간 이내 - 아직 등록 폼 작성 중일 수 있어 건드리지 않는다.
                continue;
            }

            s3PresignService.deleteObject(object.key());
            deletedCount++;
            log.info("매물 이미지 고아 객체 삭제 - key={}, lastModified={}", object.key(), object.lastModified());
        }

        log.info("매물 이미지 고아 객체 정리 완료 - 전체 {}건 중 {}건 삭제 (참조됨 {}건)",
                objects.size(), deletedCount, referencedKeys.size());
    }
}
