package com.algogyeyak.property.entity;

import com.algogyeyak.global.error.ErrorCode;
import com.algogyeyak.global.exception.BusinessException;
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
 * 매물 신고. 마켓플레이스식 "타인 매물 신고"가 아니라, 본인이 등록한 매물을 본인이 직접
 * 신고하는 자가 플래그 구조다(Property 조회/수정/삭제와 동일하게 소유자만 접근 가능).
 * risk-analysis 도메인의 시스템 자동 중복탐지와는 별개의 사용자 제보 기반 데이터로,
 * DUPLICATE 사유도 자동탐지 결과와 통합하지 않고 독립적으로 기록한다.
 */
@Entity
@Table(name = "property_report")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
public class PropertyReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "property_id", nullable = false)
    private Long propertyId;

    @Column(name = "reporter_id", nullable = false)
    private Long reporterId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private PropertyReportReason reason;

    @Column(length = 500)
    private String detail;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PropertyReportStatus status;

    // 관리자 검토 결과 - RECEIVED 상태에서만 채워진다(resolve/reject 참고).
    @Column(name = "reviewer_id")
    private Long reviewerId;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    @Column(name = "review_memo", length = 500)
    private String reviewMemo;

    @CreatedDate
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @Builder
    private PropertyReport(Long propertyId, Long reporterId, PropertyReportReason reason, String detail) {
        this.propertyId = propertyId;
        this.reporterId = reporterId;
        this.reason = reason;
        // ETC가 아닌 사유는 detail을 항상 null로 강제한다 - "그 외에는 null"이라는 API 스펙을
        // 요청값이 무엇이든 서버 쪽에서 확정 짓기 위함.
        this.detail = reason == PropertyReportReason.ETC ? detail : null;
        this.status = PropertyReportStatus.RECEIVED;
    }

    // 관리자 페이지 전용. RECEIVED에서만 RESOLVED/REJECTED로 전이할 수 있다 - 이미 검토된
    // 신고를 다시 검토하려는 시도(중복 클릭 등)는 상태 전이 오류로 거부한다.
    public void resolve(Long reviewerId, String memo) {
        transitionTo(PropertyReportStatus.RESOLVED, reviewerId, memo);
    }

    public void reject(Long reviewerId, String memo) {
        transitionTo(PropertyReportStatus.REJECTED, reviewerId, memo);
    }

    private void transitionTo(PropertyReportStatus target, Long reviewerId, String memo) {
        if (this.status != PropertyReportStatus.RECEIVED) {
            throw new BusinessException(ErrorCode.ADMIN_INVALID_STATUS_TRANSITION, "이미 검토가 완료된 신고입니다.");
        }
        this.status = target;
        this.reviewerId = reviewerId;
        this.reviewedAt = LocalDateTime.now();
        this.reviewMemo = memo;
    }
}
