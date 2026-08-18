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
 * S3에 영구히 남는 고아 이미지를 정리한다(#214). 세 가지 핵심 판단을 검증한다: 참조 없고 그레이스
 * 기간이 지났으면 삭제 / 그레이스 기간 이내면 보존 / DB에 참조돼 있으면 그레이스 기간과 무관하게 보존.
 */
@ExtendWith(MockitoExtension.class)
class PropertyImageOrphanCleanupJobTest {

    @Mock
    private S3PresignService s3PresignService;

    @Mock
    private PropertyImageRepository propertyImageRepository;

    private PropertyImageOrphanCleanupJob job;

    private static final int GRACE_PERIOD_HOURS = 24;

    @BeforeEach
    void setUp() {
        job = new PropertyImageOrphanCleanupJob(
                s3PresignService, propertyImageRepository, new PropertyImageCleanupProperties(GRACE_PERIOD_HOURS)
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
}
