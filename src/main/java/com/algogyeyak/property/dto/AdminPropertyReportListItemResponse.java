package com.algogyeyak.property.dto;

import com.algogyeyak.property.entity.Property;
import com.algogyeyak.property.entity.PropertyReport;
import com.algogyeyak.property.entity.PropertyReportReason;
import com.algogyeyak.property.entity.PropertyReportStatus;
import com.algogyeyak.user.entity.User;
import java.time.LocalDateTime;

public record AdminPropertyReportListItemResponse(
        Long id,
        Long propertyId,
        String propertyAddress,
        Long reporterId,
        String reporterNickname,
        PropertyReportReason reason,
        String detail,
        PropertyReportStatus status,
        LocalDateTime createdAt
) {
    // property/reporter는 propertyId/reporterId가 순수 FK 컬럼(JPA 연관관계 아님)이라 서비스가
    // 별도로 배치 조회해 넘겨준다 - 삭제되었거나 찾을 수 없으면 null을 그대로 반영한다.
    public static AdminPropertyReportListItemResponse of(PropertyReport report, Property property, User reporter) {
        return new AdminPropertyReportListItemResponse(
                report.getId(),
                report.getPropertyId(),
                resolveAddress(property),
                report.getReporterId(),
                reporter != null ? reporter.getNickname() : null,
                report.getReason(),
                report.getDetail(),
                report.getStatus(),
                report.getCreatedAt());
    }

    static String resolveAddress(Property property) {
        if (property == null || property.getAddress() == null) {
            return null;
        }
        var address = property.getAddress();
        return address.getRoadAddress() != null ? address.getRoadAddress() : address.getJibunAddress();
    }
}
