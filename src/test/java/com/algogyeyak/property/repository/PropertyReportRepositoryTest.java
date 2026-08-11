package com.algogyeyak.property.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.algogyeyak.property.entity.PropertyReport;
import com.algogyeyak.property.entity.PropertyReportReason;
import com.algogyeyak.property.entity.PropertyReportStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.data.domain.PageRequest;

/**
 * AdminPropertyReportControllerTest는 PropertyReportRepository를 mock으로 대체하므로 search()의
 * status/reason 정확일치가 실제로 동작하는지는 검증하지 못한다 - 실제 H2로 그 지점만 확인한다
 * (관리자 신고 목록 조회, GET /admin/property-reports).
 */
@DataJpaTest
class PropertyReportRepositoryTest {

    @Autowired
    private PropertyReportRepository propertyReportRepository;

    private PropertyReport save(Long propertyId, Long reporterId, PropertyReportReason reason) {
        return propertyReportRepository.save(PropertyReport.builder()
                .propertyId(propertyId)
                .reporterId(reporterId)
                .reason(reason)
                .detail(null)
                .build());
    }

    @Test
    void status는_정확히_일치하는_값만_반환한다() {
        PropertyReport resolved = save(1L, 10L, PropertyReportReason.PRICE_MISMATCH);
        resolved.resolve(99L, "처리 완료");
        propertyReportRepository.save(resolved);
        save(2L, 11L, PropertyReportReason.DUPLICATE);

        var result = propertyReportRepository.search(PropertyReportStatus.RESOLVED, null, PageRequest.of(0, 20));

        assertThat(result.getContent()).extracting(PropertyReport::getStatus).containsExactly(PropertyReportStatus.RESOLVED);
    }

    @Test
    void reason은_정확히_일치하는_값만_반환한다() {
        save(1L, 10L, PropertyReportReason.PRICE_MISMATCH);
        save(2L, 11L, PropertyReportReason.DUPLICATE);

        var result = propertyReportRepository.search(null, PropertyReportReason.DUPLICATE, PageRequest.of(0, 20));

        assertThat(result.getContent()).extracting(PropertyReport::getReason).containsExactly(PropertyReportReason.DUPLICATE);
    }

    @Test
    void status와_reason이_동시에_AND_조건으로_적용된다() {
        PropertyReport rejected = save(1L, 10L, PropertyReportReason.DUPLICATE);
        rejected.reject(99L, "근거 불충분");
        propertyReportRepository.save(rejected);
        // reason은 같지만 status가 다른 신고 - AND 조건이면 결과에서 빠져야 한다.
        save(2L, 11L, PropertyReportReason.DUPLICATE);

        var result = propertyReportRepository.search(
                PropertyReportStatus.REJECTED, PropertyReportReason.DUPLICATE, PageRequest.of(0, 20));

        assertThat(result.getContent()).extracting(PropertyReport::getPropertyId).containsExactly(1L);
    }

    @Test
    void 필터가_전부_없으면_전체_신고를_반환한다() {
        save(1L, 10L, PropertyReportReason.PRICE_MISMATCH);
        save(2L, 11L, PropertyReportReason.DUPLICATE);

        var result = propertyReportRepository.search(null, null, PageRequest.of(0, 20));

        assertThat(result.getTotalElements()).isEqualTo(2);
    }
}
