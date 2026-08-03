package com.algogyeyak.property.dto;

import com.algogyeyak.property.entity.Property;
import com.algogyeyak.property.entity.PropertyReport;
import com.algogyeyak.property.entity.PropertyReportReason;
import com.algogyeyak.property.entity.PropertyReportStatus;
import com.algogyeyak.user.entity.User;
import java.time.LocalDateTime;

public record AdminPropertyReportDetailResponse(
        Long id,
        Long propertyId,
        String propertyType,
        String transactionType,
        String propertyAddress,
        Long deposit,
        Long monthlyRent,
        Long reporterId,
        String reporterNickname,
        String reporterEmail,
        PropertyReportReason reason,
        String detail,
        PropertyReportStatus status,
        Long reviewerId,
        LocalDateTime reviewedAt,
        String reviewMemo,
        LocalDateTime createdAt
) {
    public static AdminPropertyReportDetailResponse of(PropertyReport report, Property property, User reporter) {
        return new AdminPropertyReportDetailResponse(
                report.getId(),
                report.getPropertyId(),
                property != null ? property.getPropertyType().name() : null,
                property != null ? property.getTransactionType().name() : null,
                AdminPropertyReportListItemResponse.resolveAddress(property),
                property != null ? property.getDeposit() : null,
                property != null ? property.getMonthlyRent() : null,
                report.getReporterId(),
                reporter != null ? reporter.getNickname() : null,
                reporter != null ? reporter.getEmail() : null,
                report.getReason(),
                report.getDetail(),
                report.getStatus(),
                report.getReviewerId(),
                report.getReviewedAt(),
                report.getReviewMemo(),
                report.getCreatedAt());
    }
}
