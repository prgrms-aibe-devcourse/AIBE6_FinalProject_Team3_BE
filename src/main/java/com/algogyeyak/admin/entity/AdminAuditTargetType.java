package com.algogyeyak.admin.entity;

/**
 * {@link AdminAuditAction}이 어떤 도메인의 리소스를 대상으로 하는지. 지금은 action만 봐도 대상
 * 도메인을 알 수 있어(예: UPDATE_ROLE → USER) 굳이 별도 컬럼이 필요 없어 보이지만, 나중에
 * "GET /admin/audit-logs?targetType=USER"처럼 도메인 단위로 조회/필터링하는 API가 생기면 매번
 * action 목록을 나열해 매핑하는 대신 이 컬럼으로 바로 걸러낼 수 있다.
 */
public enum AdminAuditTargetType {
    USER,
    CHECKLIST_TEMPLATE,
    PROPERTY_REPORT
}
