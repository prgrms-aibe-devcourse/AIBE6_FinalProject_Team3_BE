package com.algogyeyak.user.batch;

import com.algogyeyak.global.s3.dto.S3ObjectSummary;
import com.algogyeyak.global.s3.service.S3PresignService;
import com.algogyeyak.global.s3.util.S3ImagePurpose;
import com.algogyeyak.user.config.ProfileImageCleanupProperties;
import com.algogyeyak.user.repository.UserRepository;
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
 * 프로필 이미지 고아 객체 정리 배치.
 *
 * {@code UserService.deletePreviousProfileImageIfOwned()}는 새 이미지로 교체되거나 초기화된
 * 이전 프로필 이미지를 {@code S3PresignService.deleteReplacedObject()}로 best-effort 삭제한다.
 * 이 메서드는 즉시 삭제를 시도하기 전에 안전망으로 PENDING_UPLOAD_TAG를 다시 걸어두어, 즉시
 * 삭제가 실패해도 버킷 Lifecycle 규칙이 나중에 대신 정리해줄 수 있게 한다. 하지만 태깅 자체와
 * 즉시 삭제가 둘 다 실패하면(권한/네트워크 등) 그 객체는 태그도 없이 S3에 영구 고아로 남는다 -
 * Lifecycle 규칙은 PENDING_UPLOAD_TAG가 붙은 객체만 정리 대상으로 보기 때문에 이 이중 실패
 * 케이스를 잡지 못한다({@code PropertyImageOrphanCleanupJob}이 매물 이미지 쪽에서 해소하는
 * 문제와 동일한 성격, #214 참고).
 *
 * 이 배치는 S3 profile-images/ prefix 전체와 DB({@code UserRepository})에 실제로 참조된
 * profileImageUrl 집합을 대조해서, DB에 없으면서 그레이스 기간(gracePeriodHours) 이상 지난
 * 객체만 삭제한다 - 태그/Lifecycle 상태와 완전히 무관하게 동작하므로 태깅+즉시삭제 이중 실패
 * 케이스까지 포함해 정리된다.
 *
 * 기존 로직({@code UserService})은 전혀 건드리지 않는다 - 완전히 독립된 배치라서 회귀 위험이
 * 없다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProfileImageOrphanCleanupJob {

    private final S3PresignService s3PresignService;
    private final UserRepository userRepository;
    private final ProfileImageCleanupProperties properties;

    // PropertyImageOrphanCleanupJob과 동일한 이유(표본이 적으면 비율 판단이 통계적으로 의미
    // 없음)로 서킷브레이커를 표본 하한 이상에서만 적용한다.
    private static final int MIN_OBJECTS_FOR_CIRCUIT_BREAKER = 10;

    // PropertyImageOrphanCleanupJob과 동일한 이유(서버가 09~18시에만 상시 기동)로 09:30에 잡되,
    // 같은 시각에 두 배치가 동시에 S3/DB에 부하를 주지 않도록 5분 뒤로 살짝 늦춘다.
    @Scheduled(cron = "${user.profile-image-cleanup.cron}", zone = "Asia/Seoul")
    public void cleanUp() {
        Set<String> referencedKeys = userRepository.findAllProfileImageUrls().stream()
                .map(s3PresignService::extractOwnedKey)
                .flatMap(Optional::stream)
                .collect(Collectors.toSet());

        List<S3ObjectSummary> objects = s3PresignService.listObjects(S3ImagePurpose.PROFILE);
        Instant cutoff = Instant.now().minus(properties.gracePeriodHours(), ChronoUnit.HOURS);

        List<S3ObjectSummary> candidates = objects.stream()
                .filter(object -> !referencedKeys.contains(object.key()))
                .filter(object -> !object.lastModified().isAfter(cutoff))
                .toList();

        if (objects.size() >= MIN_OBJECTS_FOR_CIRCUIT_BREAKER
                && candidates.size() > objects.size() * properties.maxDeleteRatio()) {
            log.warn("프로필 이미지 고아 객체 정리 중단(서킷브레이커) - 전체 {}건 중 삭제 후보 {}건으로 "
                            + "임계 비율({})을 초과해 이번 실행을 건너뜁니다. DB 연결/환경 설정을 확인하세요.",
                    objects.size(), candidates.size(), properties.maxDeleteRatio());
            return;
        }

        for (S3ObjectSummary candidate : candidates) {
            s3PresignService.deleteObject(candidate.key());
            log.info("프로필 이미지 고아 객체 삭제 - key={}, lastModified={}", candidate.key(), candidate.lastModified());
        }

        log.info("프로필 이미지 고아 객체 정리 완료 - 전체 {}건 중 {}건 삭제 (참조됨 {}건)",
                objects.size(), candidates.size(), referencedKeys.size());
    }
}
