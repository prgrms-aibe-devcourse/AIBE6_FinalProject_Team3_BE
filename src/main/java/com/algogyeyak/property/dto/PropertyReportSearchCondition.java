package com.algogyeyak.property.dto;

import com.algogyeyak.property.entity.PropertyReportReason;
import com.algogyeyak.property.entity.PropertyReportStatus;

/**
 * 관리자 신고 목록 조회(GET /admin/property-reports) 검색/필터 조건. 전부 선택값(null 허용)이다.
 */
public record PropertyReportSearchCondition(
        PropertyReportStatus status,
        PropertyReportReason reason
) {
}
