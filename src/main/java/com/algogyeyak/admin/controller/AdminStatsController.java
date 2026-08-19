package com.algogyeyak.admin.controller;

import com.algogyeyak.admin.dto.AdminDashboardStatsResponse;
import com.algogyeyak.admin.service.AdminStatsService;
import com.algogyeyak.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 전용 통계 대시보드 API. /admin/** 는 SecurityConfig의 URL 패턴(hasRole("ADMIN"))으로
 * 우선 차단되고, 아래 @PreAuthorize는 그 매칭이 어떤 이유로든 빗나가는 경우를 위한 이중 방어다.
 */
@RestController
@RequestMapping("/admin/stats")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin - Stats", description = "관리자 전용 통계 대시보드 API")
public class AdminStatsController {

    private final AdminStatsService adminStatsService;

    // startDate/endDate를 생략하면 오늘 기준 최근 14일로 동작한다(AdminStatsService 기본값).
    @Operation(summary = "대시보드 통계 조회", description = "기간(startDate~endDate)별 관리자 대시보드 통계를 반환한다. 기간 생략 시 최근 14일 기준.")
    @GetMapping("/dashboard")
    public ResponseEntity<ApiResponse<AdminDashboardStatsResponse>> dashboard(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate
    ) {
        return ResponseEntity.ok(ApiResponse.success(adminStatsService.getDashboard(startDate, endDate)));
    }
}
