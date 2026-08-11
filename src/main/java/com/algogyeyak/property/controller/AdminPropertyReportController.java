package com.algogyeyak.property.controller;

import com.algogyeyak.auth.jwt.JwtUserPrincipal;
import com.algogyeyak.global.response.ApiResponse;
import com.algogyeyak.global.response.PageResponse;
import com.algogyeyak.property.dto.AdminPropertyReportDetailResponse;
import com.algogyeyak.property.dto.AdminPropertyReportListItemResponse;
import com.algogyeyak.property.dto.AdminPropertyReportReviewRequest;
import com.algogyeyak.property.dto.PropertyReportSearchCondition;
import com.algogyeyak.property.entity.PropertyReportReason;
import com.algogyeyak.property.entity.PropertyReportStatus;
import com.algogyeyak.property.service.AdminPropertyReportService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 전용 매물 신고 검토 API. /admin/** 는 SecurityConfig에서 ROLE_ADMIN으로만 접근 가능하다.
 */
@Slf4j
@RestController
@RequestMapping("/admin/property-reports")
@RequiredArgsConstructor
public class AdminPropertyReportController {

    private final AdminPropertyReportService adminPropertyReportService;

    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<AdminPropertyReportListItemResponse>>> list(
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable,
            @RequestParam(required = false) PropertyReportStatus status,
            @RequestParam(required = false) PropertyReportReason reason
    ) {
        PropertyReportSearchCondition condition = new PropertyReportSearchCondition(status, reason);
        return ResponseEntity.ok(ApiResponse.success(adminPropertyReportService.list(pageable, condition)));
    }

    @GetMapping("/{reportId}")
    public ResponseEntity<ApiResponse<AdminPropertyReportDetailResponse>> detail(@PathVariable Long reportId) {
        return ResponseEntity.ok(ApiResponse.success(adminPropertyReportService.getDetail(reportId)));
    }

    @PatchMapping("/{reportId}/review")
    public ResponseEntity<ApiResponse<AdminPropertyReportDetailResponse>> review(
            @AuthenticationPrincipal JwtUserPrincipal principal,
            @PathVariable Long reportId,
            @Valid @RequestBody AdminPropertyReportReviewRequest request
    ) {
        AdminPropertyReportDetailResponse response =
                adminPropertyReportService.review(principal.userId(), reportId, request.status(), request.memo());
        // 신고 처리 결과는 재조회 시 reviewerId/reviewedAt으로 남지만, 나중에 다른 관리자가 덮어쓰면
        // (review() javadoc의 알려진 한계) 이전 처리자 기록이 사라진다 - AdminPropertyReportService가
        // AdminAuditLogger로 영구 기록을 남기고, 이 로그는 실시간 관측용으로 별도 유지한다.
        log.info("관리자 액션: actorId={} action=REVIEW_PROPERTY_REPORT reportId={} newStatus={}",
                principal.userId(), reportId, request.status());
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}
