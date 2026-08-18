package com.algogyeyak.property.batch;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.algogyeyak.global.s3.dto.S3ObjectSummary;
import com.algogyeyak.global.s3.service.S3PresignService;
import com.algogyeyak.global.s3.util.S3ImagePurpose;
import com.algogyeyak.property.config.PropertyImageCleanupProperties;
import com.algogyeyak.property.repository.PropertyImageRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * PropertyImageOrphanCleanupJob은 매물 등록/수정 폼에서 사진만 올리고 끝까지 제출하지 않은 경우
 * S3에 영구히 남는 고아 이미지를 정리한다(#214). 네 가지 핵심 판단을 검증한다: 참조 없고 그레이스
 * 기간이 지났으면 삭제 / 그레이스 기간 이내면 보존 / DB에 참조돼 있으면 그레이스 기간과 무관하게 보존 /
 * 삭제 후보 비율이 임계치(max-delete-ratio)를 넘으면 서킷브레이커가 개입해 아무것도 삭제하지 않음.
 */
@ExtendWith(MockitoExtension.class)
class PropertyImageOrphanCleanupJobTest {

    @Mock
    private S3PresignService s3PresignService;

    @Mock
    private PropertyImageRepository propertyImageRepository;

    private PropertyImageOrphanCleanupJob job;

    private static final int GRACE_PERIOD_HOURS = 24;
    // 서킷브레이커 자체를 검증하는 테스트가 아닌 나머지 테스트에서는 삭제 판단 로직만 보고 싶으므로
    // 100%(=1.0)로 둬서 서킷브레이커가 개입하지 않게 한다. 서킷브레이커 동작은 별도 테스트에서
    // 더 낮은 비율로 직접 구성해 검증한다.
    private static final double MAX_DELETE_RATIO = 1.0;

    @BeforeEach
    void setUp() {
        job = new PropertyImageOrphanCleanupJob(
                s3PresignService, propertyImageRepository,
                new PropertyImageCleanupProperties(GRACE_PERIOD_HOURS, MAX_DELETE_RATIO)
        );
    }

    @Test
    void 참조가_없고_그레이스_기간이_지난_객체는_삭제한다() {
        String orphanKey = "property-images/1/orphan.jpg";
        Instant old = Instant.now().minus(GRACE_PERIOD_HOURS + 1, ChronoUnit.HOURS);
        when(propertyImageRepository.findAllImageUrls()).thenReturn(List.of());
        when(s3PresignService.listObjects(S3ImagePurpose.PROPERTY))
                .thenReturn(List.of(new S3ObjectSummary(orphanKey, old)));

        job.cleanUp();

        verify(s3PresignService).deleteObject(orphanKey);
    }

    @Test
    void 그레이스_기간_이내_객체는_보존한다() {
        String recentKey = "property-images/1/recent.jpg";
        Instant recent = Instant.now().minus(1, ChronoUnit.HOURS);
        when(propertyImageRepository.findAllImageUrls()).thenReturn(List.of());
        when(s3PresignService.listObjects(S3ImagePurpose.PROPERTY))
                .thenReturn(List.of(new S3ObjectSummary(recentKey, recent)));

        job.cleanUp();

        verify(s3PresignService, never()).deleteObject(anyString());
    }

    @Test
    void DB에_참조된_객체는_그레이스_기간이_지나도_보존한다() {
        String referencedKey = "property-images/1/used.jpg";
        String imageUrl = "https://example-bucket.s3.ap-northeast-2.amazonaws.com/" + referencedKey;
        Instant old = Instant.now().minus(GRACE_PERIOD_HOURS + 1, ChronoUnit.HOURS);
        when(propertyImageRepository.findAllImageUrls()).thenReturn(List.of(imageUrl));
        when(s3PresignService.extractOwnedKey(imageUrl)).thenReturn(Optional.of(referencedKey));
        when(s3PresignService.listObjects(S3ImagePurpose.PROPERTY))
                .thenReturn(List.of(new S3ObjectSummary(referencedKey, old)));

        job.cleanUp();

        verify(s3PresignService, never()).deleteObject(anyString());
    }

    @Test
    void 삭제_후보_비율이_임계치를_넘으면_아무것도_삭제하지_않는다() {
        // DB 조회가 비정상적으로 비어(연결 실패/잘못된 환경 등) 참조된 이미지가 하나도 없다고 나온
        // 상황을 재현한다 - 실제로 2026-08-18에 로컬 테스트에서 이 시나리오로 65건이 전부 삭제됐다.
        double strictRatio = 0.5;
        PropertyImageOrphanCleanupJob strictJob = new PropertyImageOrphanCleanupJob(
                s3PresignService, propertyImageRepository,
                new PropertyImageCleanupProperties(GRACE_PERIOD_HOURS, strictRatio)
        );
        Instant old = Instant.now().minus(GRACE_PERIOD_HOURS + 1, ChronoUnit.HOURS);
        List<S3ObjectSummary> allOrphaned = List.of(
                new S3ObjectSummary("property-images/1/a.jpg", old),
                new S3ObjectSummary("property-images/1/b.jpg", old),
                new S3ObjectSummary("property-images/1/c.jpg", old),
                new S3ObjectSummary("property-images/1/d.jpg", old)
        );
        when(propertyImageRepository.findAllImageUrls()).thenReturn(List.of());
        when(s3PresignService.listObjects(S3ImagePurpose.PROPERTY)).thenReturn(allOrphaned);

        strictJob.cleanUp();

        verify(s3PresignService, never()).deleteObject(anyString());
    }
}
