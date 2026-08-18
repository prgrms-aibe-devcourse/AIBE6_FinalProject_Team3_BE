package com.algogyeyak.admin.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// 관리자 컨트롤러들이 각자 조금씩 다른 포맷 문자열로 실시간 관측용(Prometheus/Grafana) 텍스트
// 로그를 남기던 중복(AdminUserController/AdminPropertyReportController/
// AdminChecklistTemplateController, 2026-08-12 전수조사 결과 admin-design.md 코드 품질 2번)을
// 이 공용 유틸로 정리했다. AdminAuditLogger(DB 영구 기록)와는 별개 축이라 통합하지 않았다 -
// AdminAuditLogger.log()는 실제 변경과 같은 트랜잭션 안 서비스 레이어에서만 호출 가능하도록
// 강제되어 있는데(클래스 상단 javadoc 참고), 이 텍스트 로그는 컨트롤러 레이어에서 그냥 관측용으로
// 남기는 것이라 그 제약을 그대로 적용할 이유가 없다. 필드명(targetField)은 호출부가 직접 정해
// 기존 로그 문구(targetUserId/reportId/templateId 등)를 그대로 유지한다 - 이미 이 로그를 검색하는
// Grafana 대시보드/알림이 있을 수 있어 필드명 자체를 임의로 통일하지 않았다.
public final class AdminActionLog {

    private static final Logger log = LoggerFactory.getLogger(AdminActionLog.class);

    private AdminActionLog() {
        // 유틸리티 클래스, 인스턴스화 방지
    }

    public static void record(Long actorId, String action, String targetField, Object targetId) {
        log.info("관리자 액션: actorId={} action={} {}={}", actorId, action, targetField, targetId);
    }

    public static void record(
            Long actorId, String action, String targetField, Object targetId, String detailField, Object detailValue) {
        log.info("관리자 액션: actorId={} action={} {}={} {}={}",
                actorId, action, targetField, targetId, detailField, detailValue);
    }
}
