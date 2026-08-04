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
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}
