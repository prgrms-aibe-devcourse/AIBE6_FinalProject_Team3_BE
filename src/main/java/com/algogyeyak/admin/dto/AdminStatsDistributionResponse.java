package com.algogyeyak.admin.dto;

import com.algogyeyak.property.entity.PropertyReportReason;
import com.algogyeyak.user.enums.Role;
import java.util.List;

public record AdminStatsDistributionResponse(
        List<RoleCount> byRole,
        List<ReportReasonCount> byReportReason
) {
    public record RoleCount(Role role, long count) {
    }

    public record ReportReasonCount(PropertyReportReason reason, long count) {
    }
}
