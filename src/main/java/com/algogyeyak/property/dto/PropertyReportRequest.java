package com.algogyeyak.property.dto;

import com.algogyeyak.property.entity.PropertyReportReason;

/**
 * 매물 신고 요청. reason 누락/ETC인데 detail 누락은 각각 REPORT_REASON_REQUIRED/REPORT_DETAIL_REQUIRED로
 * 구분해서 응답해야 하므로, Bean Validation(@NotNull) 대신 Service에서 직접 검증한다
 * (Bean Validation을 쓰면 두 케이스 모두 COMMON_400으로 뭉뚱그려져 스펙과 어긋난다).
 */
public record PropertyReportRequest(
        PropertyReportReason reason,
        String detail
) {
}
