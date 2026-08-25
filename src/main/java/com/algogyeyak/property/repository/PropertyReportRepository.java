package com.algogyeyak.property.repository;

import com.algogyeyak.property.entity.PropertyReport;
import com.algogyeyak.property.entity.PropertyReportReason;
import com.algogyeyak.property.entity.PropertyReportStatus;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PropertyReportRepository extends JpaRepository<PropertyReport, Long> {

    /**
     * 동일 사용자가 동일 매물을 이미 신고했는지 확인 (REPORT_DUPLICATE 판단용).
     */
    boolean existsByPropertyIdAndReporterId(Long propertyId, Long reporterId);

    /**
     * 관리자 페이지 신고 목록 조회. status/reason 둘 다 선택 조건이다(null이면 필터링하지 않음).
     */
    @Query("""
            SELECT r FROM PropertyReport r
            WHERE (:status IS NULL OR r.status = :status)
              AND (:reason IS NULL OR r.reason = :reason)
            """)
    Page<PropertyReport> search(
            @Param("status") PropertyReportStatus status,
            @Param("reason") PropertyReportReason reason,
            Pageable pageable
    );

    // 관리자 통계 대시보드: 처리 대기(RECEIVED) 신고 수 카드용(기간 내 접수).
    long countByStatusAndCreatedAtBetween(PropertyReportStatus status, LocalDateTime start, LocalDateTime end);

    // 관리자 통계 대시보드: 신고 사유별 분포용(기간 내 접수). reason 값마다 별도 COUNT 쿼리를
    // 날리는 대신(enum 5개 = 쿼리 5번) 한 번의 GROUP BY로 집계한다 - 결과에는 해당 기간에 실제로
    // 접수된 사유만 나타나므로, 호출부(AdminStatsService)가 나머지 사유를 0건으로 채워야 한다.
    @Query("""
            SELECT r.reason AS reason, COUNT(r) AS count
            FROM PropertyReport r
            WHERE r.createdAt >= :start AND r.createdAt < :end
            GROUP BY r.reason
            """)
    List<ReasonCount> countGroupedByReasonAndCreatedAtBetween(
            @Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    interface ReasonCount {
        PropertyReportReason getReason();

        long getCount();
    }
}
