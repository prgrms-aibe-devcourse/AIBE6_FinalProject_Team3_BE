package com.algogyeyak.admin.dto;

public record AdminStatsSummaryResponse(
        long totalUsers,
        long totalProperties,
        long pendingReports
) {
}
