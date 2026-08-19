package com.algogyeyak.property.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;

import com.algogyeyak.admin.dto.AdminBulkActionResponse;
import com.algogyeyak.admin.entity.AdminAuditAction;
import com.algogyeyak.admin.service.AdminAuditLogger;
import com.algogyeyak.property.entity.PropertyReport;
import com.algogyeyak.property.entity.PropertyReportReason;
import com.algogyeyak.property.entity.PropertyReportStatus;
import com.algogyeyak.property.repository.PropertyReportRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * AdminUserServiceBulkPartialFailureIntegrationTest와 동일한 이유 - bulkReview()가 항목마다
 * review()를 REQUIRES_NEW로 감싸기 전에는, 항목 처리 도중(감사 로그 등) BusinessException이
 * 아닌 예외가 나도 이미 실행된 상태 전이가 bulkReview() 자신의 (유일한) 트랜잭션에 남아 결국
 * 커밋됐다 - 응답은 실패로 보고하면서 실제로는 DB가 바뀌는 모순이었다. 이 테스트는 real H2 +
 * real 트랜잭션 매니저로 그 지점을 직접 확인한다.
 */
@SpringBootTest
class AdminPropertyReportServiceBulkPartialFailureIntegrationTest {

    @Autowired
    private AdminPropertyReportService adminPropertyReportService;

    @Autowired
    private PropertyReportRepository propertyReportRepository;

    @MockitoBean
    private AdminAuditLogger adminAuditLogger;

    private PropertyReport saveReport(Long propertyId, Long reporterId) {
        PropertyReport report = PropertyReport.builder()
                .propertyId(propertyId)
                .reporterId(reporterId)
                .reason(PropertyReportReason.PRICE_MISMATCH)
                .detail(null)
                .build();
        return propertyReportRepository.saveAndFlush(report);
    }

    @Test
    void 감사로그_실패한_신고는_상태전이가_실행됐어도_실제로는_롤백된다() {
        PropertyReport succeeds = saveReport(101L, 201L);
        PropertyReport fails = saveReport(102L, 202L);
        Long reviewerId = 999_000L; // 두 신고 모두의 reporterId와 달라 본인 신고 셀프 검토 가드에 안 걸림

        doThrow(new IllegalStateException("감사 로그 직렬화 실패"))
                .when(adminAuditLogger).log(eq(reviewerId), eq(AdminAuditAction.REVIEW_PROPERTY_REPORT), eq(fails.getId()), any());

        AdminBulkActionResponse result = adminPropertyReportService.bulkReview(
                reviewerId, java.util.List.of(succeeds.getId(), fails.getId()), PropertyReportStatus.RESOLVED, null);

        assertThat(result.succeededIds()).containsExactly(succeeds.getId());
        assertThat(result.failures()).hasSize(1);
        assertThat(result.failures().get(0).id()).isEqualTo(fails.getId());

        // 핵심 검증 - fails 신고는 review()의 상태 전이 자체는 실행됐지만(감사 로그 실패가 그
        // 이후 단계이므로), REQUIRES_NEW 트랜잭션이 롤백되어 최종적으로 DB에는 반영되지 않아야
        // 한다. succeeds 신고는 정상적으로 RESOLVED로 남아있어야 한다.
        PropertyReport reloadedSucceeds = propertyReportRepository.findById(succeeds.getId()).orElseThrow();
        PropertyReport reloadedFails = propertyReportRepository.findById(fails.getId()).orElseThrow();
        assertThat(reloadedSucceeds.getStatus()).isEqualTo(PropertyReportStatus.RESOLVED);
        assertThat(reloadedFails.getStatus()).isEqualTo(PropertyReportStatus.RECEIVED);
    }
}
