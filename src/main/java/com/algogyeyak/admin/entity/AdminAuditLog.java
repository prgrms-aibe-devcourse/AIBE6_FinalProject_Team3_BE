package com.algogyeyak.admin.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * 관리자 액션(유저 권한/상태 변경, 체크리스트 문항 생성/수정/삭제, 매물 신고 검토)의 영구 감사 기록.
 * 기존에 각 컨트롤러가 남기던 log.info() 텍스트 로그는 실시간 관측(Prometheus/Grafana)용으로 계속
 * 병행하고, 이 테이블은 "누가 언제 무엇을 바꿨는지" 조회 가능한 이력으로 별도 관리한다.
 *
 * <p>정책: 이 행 저장은 실제 변경(role 변경, 문항 삭제 등)과 같은 트랜잭션 안에서 이뤄진다({@link
 * com.algogyeyak.admin.service.AdminAuditLogger} 참고) - 감사 로그 저장에 실패하면(제약 위반 등)
 * 그 실제 변경도 함께 롤백된다. "감사 기록을 남길 수 없으면 관리자 변경도 실패해야 한다"는 의도적
 * 결정이다.
 *
 * <p>스키마는 현재 ddl-auto: update(CLAUDE.md에 이미 명시된 임시조치)에 의존한다 - Flyway/Liquibase
 * 전환 시 이 테이블도 정식 마이그레이션으로 옮겨야 한다.
 */
@Entity
@Table(name = "admin_audit_logs")
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AdminAuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "admin_user_id", nullable = false)
    private Long adminUserId;

    // 행위자(adminUserId)의 계정이 나중에 탈퇴/이메일 변경되어도 "당시 누구였는지"가 흐려지지
    // 않도록 기록 시점의 이메일을 스냅샷으로 함께 남긴다. adminUserId만으로는 후속 변경에 약하다.
    @Column(name = "admin_email_snapshot", length = 255)
    private String adminEmailSnapshot;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private AdminAuditAction action;

    // action에서 그대로 유추 가능한 값이지만(예: UPDATE_ROLE → USER), 나중에 도메인 단위 조회 API가
    // 생겼을 때 action 목록을 매번 나열하지 않고 바로 필터링할 수 있도록 별도 컬럼으로 저장한다
    // (AdminAuditTargetType 참고). action과 항상 1:1로 맞으므로 AdminAuditLog.of()에서 자동으로
    // 채워지고, 호출부가 직접 넘기지는 않는다.
    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 30)
    private AdminAuditTargetType targetType;

    @Column(name = "target_id", nullable = false)
    private Long targetId;

    // 사람이 읽기 좋은 요약 텍스트 대신 JSON 문자열로 저장한다 - "ADMIN으로 승격한 기록만 조회"류의
    // 필터링 요구가 생겨도 detail 안의 특정 필드로 걸러낼 수 있게 하기 위함.
    @Column(columnDefinition = "TEXT")
    private String detail;

    @CreatedDate
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @Builder
    private AdminAuditLog(
            Long adminUserId, String adminEmailSnapshot, AdminAuditAction action,
            AdminAuditTargetType targetType, Long targetId, String detail) {
        this.adminUserId = adminUserId;
        this.adminEmailSnapshot = adminEmailSnapshot;
        this.action = action;
        this.targetType = targetType;
        this.targetId = targetId;
        this.detail = detail;
    }

    // targetType은 action.targetType()에서 자동으로 채운다 - 호출부(AdminAuditLogger)가 매번
    // 대응하는 타입을 직접 골라 넘기면, action은 바꾸고 targetType은 안 바꾸는 실수가 생길 수 있다.
    public static AdminAuditLog of(
            Long adminUserId, String adminEmailSnapshot, AdminAuditAction action, Long targetId, String detail) {
        return AdminAuditLog.builder()
                .adminUserId(adminUserId)
                .adminEmailSnapshot(adminEmailSnapshot)
                .action(action)
                .targetType(action.targetType())
                .targetId(targetId)
                .detail(detail)
                .build();
    }
}
