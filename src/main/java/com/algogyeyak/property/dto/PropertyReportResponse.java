package com.algogyeyak.property.dto;

import com.algogyeyak.property.entity.PropertyReport;
import java.time.LocalDateTime;

public record PropertyReportResponse(
        Long reportId,
        Long propertyId,
        String reason,
        String detail,
        String status,
        LocalDateTime createdAt
) {

    public static PropertyReportResponse from(PropertyReport report) {
        return new PropertyReportResponse(
                report.getId(),
                report.getPropertyId(),
                report.getReason().name(),
                report.getDetail(),
                report.getStatus().name(),
                report.getCreatedAt()
        );
    }
}
