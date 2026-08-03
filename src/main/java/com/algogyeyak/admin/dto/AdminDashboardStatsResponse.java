package com.algogyeyak.admin.dto;

public record AdminDashboardStatsResponse(
        AdminStatsSummaryResponse summary,
        AdminStatsTrendResponse trends,
        AdminStatsDistributionResponse distributions
) {
}
