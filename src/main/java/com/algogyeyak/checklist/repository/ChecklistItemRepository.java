package com.algogyeyak.checklist.repository;

import com.algogyeyak.checklist.entity.ChecklistItem;
import com.algogyeyak.checklist.entity.ChecklistItemCode;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChecklistItemRepository extends JpaRepository<ChecklistItem, Long> {

    /**
     * risk-analysis가 "최근 소유권 변경 + 높은 전세가율" 보조 신호를 만들 때, 문항 하나(예:
     * OWNERSHIP_ACQUISITION_DATE)의 값만 가볍게 조회하기 위한 단건 쿼리. 매물당 체크리스트가
     * 최대 1개라(uk_checklist_user_property) Optional 단건으로 충분하다.
     */
    Optional<ChecklistItem> findByChecklist_Property_IdAndCode(Long propertyId, ChecklistItemCode code);

    /**
     * 매물/체크리스트 목록 카드에 진행률(%)·주의 항목 개수를 붙이기 위한 집계 조회. 유저가 가진
     * 체크리스트를 문항 단위로 GROUP BY property.id 해서 propertyId별 (전체 문항 수, 체크된 문항 수,
     * 주의 항목 수)를 한 번에 가져온다 - 목록 페이지에 매물이 몇 건이든 이 쿼리 하나로 끝나서 N+1이
     * 생기지 않는다(Checklist.items는 LAZY라 엔티티를 그대로 순회하면 컬렉션 N+1이 생기는데, 그걸
     * 피하려고 엔티티 대신 집계값만 프로젝션으로 받는다). 체크리스트:매물이 1:1이라 checklist.id가
     * 아니라 property.id로 묶어도 결과는 동일하다 — GET /checklists(체크리스트 목록)도 이 쿼리를
     * 그대로 재사용한다(ChecklistOverviewResponse의 progressPercent/cautionCount 필드 참고).
     * issueCount는 ChecklistItem.hasIssue()(issueFound가 true이거나 userNote가 있으면 주의 항목)와
     * 동일한 조건을 CASE WHEN으로 재현한 것 - 엔티티 메서드를 그대로 SQL로 옮길 수는 없어 조건이
     * 어긋나지 않도록 hasIssue() 변경 시 이 쿼리도 함께 고쳐야 한다.
     */
    @Query("""
            SELECT i.checklist.property.id AS propertyId,
                   COUNT(i) AS totalCount,
                   SUM(CASE WHEN i.checked = true THEN 1 ELSE 0 END) AS checkedCount,
                   SUM(CASE WHEN i.issueFound = true OR i.userNote IS NOT NULL THEN 1 ELSE 0 END) AS issueCount
            FROM ChecklistItem i
            WHERE i.checklist.user.id = :userId
            GROUP BY i.checklist.property.id
            """)
    List<ChecklistProgressProjection> findProgressByUserId(@Param("userId") Long userId);

    interface ChecklistProgressProjection {
        Long getPropertyId();

        long getTotalCount();

        long getCheckedCount();

        long getIssueCount();
    }
}
