package com.algogyeyak.admin.dto;

// 세 값 전부 "전체 누적"이 아니라 대시보드 조회 기간(start~end) 내 신규 발생분이다
// (AdminStatsService.summary() 참고) - 예전 필드명(totalUsers 등)이 이 사실과 반대로 읽혀
// API 계약을 헷갈리게 했던 것을 정정했다.
public record AdminStatsSummaryResponse(
        long newUsers,
        long newProperties,
        long newPendingReports
) {
}
