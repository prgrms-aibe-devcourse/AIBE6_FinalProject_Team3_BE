package com.algogyeyak.admin.controller;

import com.algogyeyak.admin.dto.AdminDashboardStatsResponse;
import com.algogyeyak.admin.service.AdminStatsService;
import com.algogyeyak.global.response.ApiResponse;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 전용 통계 대시보드 API. /admin/** 는 SecurityConfig에서 ROLE_ADMIN으로만 접근 가능하다.
 */
@RestController
@RequestMapping("/admin/stats")
@RequiredArgsConstructor
public class AdminStatsController {

    private final AdminStatsService adminStatsService;

    // startDate/endDate를 생략하면 오늘 기준 최근 14일로 동작한다(AdminStatsService 기본값).
    @GetMapping("/dashboard")
    public ResponseEntity<ApiResponse<AdminDashboardStatsResponse>> dashboard(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate
    ) {
        return ResponseEntity.ok(ApiResponse.success(adminStatsService.getDashboard(startDate, endDate)));
    }
}
