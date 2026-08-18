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

    // 서킷브레이커(maxDeleteRatio)는 "비율"로 판단하기 때문에 전체 객체 수가 적을 때는 오히려
    // 오작동한다 - 예를 들어 정상적으로 이탈해서 남은 고아 이미지 2개가 전체 4개 중 절반이면
    // 정상적인 소량 정리인데도 비율만 보면 재앙 상황과 구분이 안 된다. 표본이 이 값 미만이면
    // 비율 판단이 통계적으로 의미가 없다고 보고 서킷브레이커 자체를 적용하지 않는다.
    private static final int MIN_OBJECTS_FOR_CIRCUIT_BREAKER = 10;

    // zone을 명시하지 않으면 서버(JVM)의 기본 타임존을 따르는데, 배포 환경에 타임존 설정이 따로
    // 없어 컨테이너가 UTC로 뜨면 "09:30"이 실제로는 KST 18:30에 실행돼 서버 운영시간(09~18시) 밖으로
    // 밀려날 수 있다 - 서버/로컬 환경 설정과 무관하게 항상 KST 기준으로 돌도록 여기서 고정한다.
    @Scheduled(cron = "${property.image-cleanup.cron}", zone = "Asia/Seoul")
    public void cleanUp() {
        Set<String> referencedKeys = propertyImageRepository.findAllImageUrls().stream()
                .map(s3PresignService::extractOwnedKey)
                .flatMap(Optional::stream)
                .collect(Collectors.toSet());

        List<S3ObjectSummary> objects = s3PresignService.listObjects(S3ImagePurpose.PROPERTY);
        Instant cutoff = Instant.now().minus(properties.gracePeriodHours(), ChronoUnit.HOURS);

        List<S3ObjectSummary> candidates = objects.stream()
                .filter(object -> !referencedKeys.contains(object.key()))
                .filter(object -> !object.lastModified().isAfter(cutoff))
                .toList();

        // 서킷브레이커: 삭제 후보가 전체 대비 비정상적으로 큰 비율을 차지하면 이번 실행을 통째로
        // 건너뛴다. DB 조회가 잘못된 환경/연결 실패 등으로 비정상적으로 비면 "참조된 이미지가 거의
        // 없다"고 오판해 정상 운영 중인 이미지까지 통째로 지워버릴 수 있다(2026-08-18 실제 재현됨).
        // 단, 표본이 MIN_OBJECTS_FOR_CIRCUIT_BREAKER 미만이면 비율 자체가 의미 없으므로 적용하지 않는다.
        if (objects.size() >= MIN_OBJECTS_FOR_CIRCUIT_BREAKER
                && candidates.size() > objects.size() * properties.maxDeleteRatio()) {
            log.warn("매물 이미지 고아 객체 정리 중단(서킷브레이커) - 전체 {}건 중 삭제 후보 {}건으로 "
                            + "임계 비율({})을 초과해 이번 실행을 건너뜁니다. DB 연결/환경 설정을 확인하세요.",
                    objects.size(), candidates.size(), properties.maxDeleteRatio());
            return;
        }

        for (S3ObjectSummary candidate : candidates) {
            s3PresignService.deleteObject(candidate.key());
            log.info("매물 이미지 고아 객체 삭제 - key={}, lastModified={}", candidate.key(), candidate.lastModified());
        }

        log.info("매물 이미지 고아 객체 정리 완료 - 전체 {}건 중 {}건 삭제 (참조됨 {}건)",
                objects.size(), candidates.size(), referencedKeys.size());
    }
}
