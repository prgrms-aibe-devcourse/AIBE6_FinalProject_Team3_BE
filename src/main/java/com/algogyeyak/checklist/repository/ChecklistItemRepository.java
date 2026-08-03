package com.algogyeyak.checklist.repository;

import com.algogyeyak.checklist.entity.ChecklistItem;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChecklistItemRepository extends JpaRepository<ChecklistItem, Long> {

    /**
     * 매물 목록 카드에 체크리스트 진행률(%)을 붙이기 위한 집계 조회. 유저가 가진 체크리스트를
     * 문항 단위로 GROUP BY property.id 해서 propertyId별 (전체 문항 수, 체크된 문항 수)를 한 번에
     * 가져온다 - 목록 페이지에 매물이 몇 건이든 이 쿼리 하나로 끝나서 N+1이 생기지 않는다
     * (Checklist.items는 LAZY라 엔티티를 그대로 순회하면 컬렉션 N+1이 생기는데, 그걸 피하려고
     * 엔티티 대신 집계값만 프로젝션으로 받는다).
     */
    @Query("""
            SELECT i.checklist.property.id AS propertyId,
                   COUNT(i) AS totalCount,
                   SUM(CASE WHEN i.checked = true THEN 1 ELSE 0 END) AS checkedCount
            FROM ChecklistItem i
            WHERE i.checklist.user.id = :userId
            GROUP BY i.checklist.property.id
            """)
    List<ChecklistProgressProjection> findProgressByUserId(@Param("userId") Long userId);

    interface ChecklistProgressProjection {
        Long getPropertyId();

        long getTotalCount();

        long getCheckedCount();
    }
}
