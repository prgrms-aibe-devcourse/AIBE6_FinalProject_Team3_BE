package com.algogyeyak.property.entity;

/**
 * 매물 신고 처리 상태. 접수(RECEIVED) 후 관리자가 검토해 조치완료(RESOLVED) 또는 반려(REJECTED)로
 * 전이한다 - RECEIVED에서만 두 상태로 전이 가능하고, 그 외 전이는 허용하지 않는다(PropertyReport 참고).
 */
public enum PropertyReportStatus {
    RECEIVED, RESOLVED, REJECTED
}
