package com.algogyeyak.property.dto;

import com.algogyeyak.property.entity.Property;
import com.algogyeyak.property.entity.PropertyReport;
import com.algogyeyak.property.entity.PropertyReportReason;
import com.algogyeyak.property.entity.PropertyReportStatus;
import com.algogyeyak.user.entity.User;
import java.time.LocalDateTime;

public record AdminPropertyReportDetailResponse(
        Long id,
        String propertyType,
        String transactionType,
        String propertyAddress,
        Long reporterId,
        String reporterNickname,
        String reporterEmail,
        PropertyReportReason reason,
        String detail,
        PropertyReportStatus status,
        LocalDateTime reviewedAt,
        String reviewMemo,
        LocalDateTime createdAt
) {
    public static AdminPropertyReportDetailResponse of(PropertyReport report, Property property, User reporter) {
        return new AdminPropertyReportDetailResponse(
                report.getId(),
                property != null ? property.getPropertyType().name() : null,
                property != null ? property.getTransactionType().name() : null,
                AdminPropertyReportListItemResponse.resolveAddress(property),
                report.getReporterId(),
                reporter != null ? reporter.getNickname() : null,
                reporter != null ? reporter.getEmail() : null,
                report.getReason(),
                report.getDetail(),
                report.getStatus(),
                report.getReviewedAt(),
                report.getReviewMemo(),
                report.getCreatedAt());
    }
}
