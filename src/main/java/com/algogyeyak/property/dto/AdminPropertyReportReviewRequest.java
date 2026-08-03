package com.algogyeyak.property.dto;

import com.algogyeyak.property.entity.PropertyReportStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

// status는 RESOLVED/REJECTED만 허용한다 - RECEIVED로 되돌리거나 그대로 두는 것은 이 API의 목적이 아니다
// (AdminPropertyReportService에서 검증).
public record AdminPropertyReportReviewRequest(
        @NotNull PropertyReportStatus status,
        @Size(max = 500, message = "메모는 500자를 넘을 수 없습니다.") String memo
) {
}
