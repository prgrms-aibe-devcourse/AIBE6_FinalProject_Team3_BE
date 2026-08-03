package com.algogyeyak.admin.dto;

import com.algogyeyak.property.entity.PropertyReportReason;
import java.util.List;

public record AdminStatsDistributionResponse(
        List<PropertyRegistrationCount> byPropertyRegistration,
        List<ReportReasonCount> byReportReason
) {
    public record PropertyRegistrationCount(boolean registered, long count) {
    }

    public record ReportReasonCount(PropertyReportReason reason, long count) {
    }
}
