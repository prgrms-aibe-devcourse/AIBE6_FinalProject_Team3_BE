package com.algogyeyak.property.controller;

import com.algogyeyak.admin.dto.AdminBulkActionResponse;
import com.algogyeyak.admin.service.AdminActionLog;
import com.algogyeyak.auth.jwt.JwtUserPrincipal;
import com.algogyeyak.global.response.ApiResponse;
import com.algogyeyak.global.response.PageResponse;
import com.algogyeyak.property.dto.AdminPropertyReportBulkReviewRequest;
import com.algogyeyak.property.dto.AdminPropertyReportDetailResponse;
import com.algogyeyak.property.dto.AdminPropertyReportListItemResponse;
import com.algogyeyak.property.dto.AdminPropertyReportReviewRequest;
import com.algogyeyak.property.dto.PropertyReportSearchCondition;
import com.algogyeyak.property.entity.PropertyReportReason;
import com.algogyeyak.property.entity.PropertyReportStatus;
import com.algogyeyak.property.service.AdminPropertyReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 전용 매물 신고 검토 API. /admin/** 는 SecurityConfig의 URL 패턴(hasRole("ADMIN"))으로
 * 우선 차단되고, 아래 @PreAuthorize는 그 매칭이 어떤 이유로든 빗나가는 경우를 위한 이중 방어다.
 */
@RestController
@RequestMapping("/admin/property-reports")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin - Property Report", description = "관리자 전용 매물 신고 검토 API")
public class AdminPropertyReportController {

    private final AdminPropertyReportService adminPropertyReportService;

    @Operation(summary = "매물 신고 목록 조회", description = "상태/사유로 검색·페이지네이션이 가능한 매물 신고 목록을 반환한다.")
    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<AdminPropertyReportListItemResponse>>> list(
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable,
            @RequestParam(required = false) PropertyReportStatus status,
            @RequestParam(required = false) PropertyReportReason reason
    ) {
        PropertyReportSearchCondition condition = new PropertyReportSearchCondition(status, reason);
        return ResponseEntity.ok(ApiResponse.success(adminPropertyReportService.list(pageable, condition)));
    }

    @Operation(summary = "매물 신고 상세 조회", description = "매물/신고자 정보를 함께 합성한 신고 상세를 반환한다.")
    @GetMapping("/{reportId}")
    public ResponseEntity<ApiResponse<AdminPropertyReportDetailResponse>> detail(@PathVariable Long reportId) {
        return ResponseEntity.ok(ApiResponse.success(adminPropertyReportService.getDetail(reportId)));
    }

    @Operation(summary = "매물 신고 검토", description = "신고를 조치완료(RESOLVED) 또는 반려(REJECTED)로 처리한다. 신고자 본인은 검토자로 지정할 수 없다.")
    @PatchMapping("/{reportId}/review")
    public ResponseEntity<ApiResponse<AdminPropertyReportDetailResponse>> review(
            @AuthenticationPrincipal JwtUserPrincipal principal,
            @PathVariable Long reportId,
            @Valid @RequestBody AdminPropertyReportReviewRequest request
    ) {
        AdminPropertyReportDetailResponse response =
                adminPropertyReportService.review(principal.userId(), principal.email(), reportId, request.status(), request.memo());
        // 신고 처리 결과는 재조회 시 reviewerId/reviewedAt으로 남지만, 나중에 다른 관리자가 덮어쓰면
        // (review() javadoc의 알려진 한계) 이전 처리자 기록이 사라진다 - AdminPropertyReportService가
        // AdminAuditLogger로 영구 기록을 남기고, 이 로그는 실시간 관측용으로 별도 유지한다.
        AdminActionLog.record(principal.userId(), "REVIEW_PROPERTY_REPORT", "reportId", reportId, "newStatus", request.status());
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @Operation(summary = "매물 신고 일괄 검토", description = "여러 신고를 한 번에 조치완료/반려 처리한다. 항목별로 성공/실패가 갈릴 수 있으며(본인 신고 셀프 검토 금지 등), 응답에 성공/실패 id가 나뉘어 담긴다.")
    @PatchMapping("/bulk-review")
    public ResponseEntity<ApiResponse<AdminBulkActionResponse>> bulkReview(
            @AuthenticationPrincipal JwtUserPrincipal principal,
            @Valid @RequestBody AdminPropertyReportBulkReviewRequest request
    ) {
        AdminBulkActionResponse result = adminPropertyReportService.bulkReview(
                principal.userId(), principal.email(), request.reportIds(), request.status(), request.memo());
        // 일괄 처리는 항목별로 성공/실패가 갈릴 수 있어(AdminBulkActionResponse javadoc 참고),
        // 요청받은 id 전체가 아니라 실제로 성공한 id만 기록한다 - 안 그러면 배치가 전부 실패해도
        // (예: 이미 처리됐거나 본인이 신고한 건만 골라 보낸 경우) 이 관측용 로그에는 "처리함"으로
        // 남아 실제 변경이 없었는데도 있었던 것처럼 보인다.
        if (!result.succeededIds().isEmpty()) {
            AdminActionLog.record(principal.userId(), "BULK_REVIEW_PROPERTY_REPORT", "reportIds", result.succeededIds(),
                    "newStatus", request.status());
        }
        return ResponseEntity.ok(ApiResponse.success(result));
    }
}
