package com.algogyeyak.property.entity;

/**
 * 매물 신고 처리 상태. MVP 범위에서는 접수(RECEIVED)만 사용하고 자동 처리는 하지 않는다 —
 * 관리자 검토 등 후속 처리 상태는 이후 별도 이슈에서 추가한다.
 */
public enum PropertyReportStatus {
    RECEIVED
}
