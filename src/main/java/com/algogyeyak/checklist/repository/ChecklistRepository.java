package com.algogyeyak.checklist.repository;

import com.algogyeyak.checklist.entity.Checklist;
import com.algogyeyak.property.entity.PropertyStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChecklistRepository extends JpaRepository<Checklist, Long> {

    // 유저-매물 조합당 활성 체크리스트는 1개뿐이므로, 생성 요청이 멱등인지 확인할 때 사용한다.
    Optional<Checklist> findByUserIdAndPropertyId(Long userId, Long propertyId);

    // 매물 하나당 체크리스트는(소유자만 만들 수 있어) 최대 1개뿐이라, propertyId만으로 조회해도 안전하다.
    // getChecklist()에서 "체크리스트 없음(404)"과 "본인 소유 아님(403)"을 구분하기 위해 사용한다.
    Optional<Checklist> findByPropertyId(Long propertyId);

    // "내 체크리스트 목록"(GET /checklists) 조회에서, 유저의 매물 목록과 매칭하기 위해 한 번에 가져온다.
    List<Checklist> findAllByUserId(Long userId);

    // GET /checklists 페이지네이션용 - Property를 기준으로 Checklist를 LEFT JOIN해서, 체크리스트를
    // 아직 시작 안 한 매물도 함께 페이지네이션 대상에 포함시킨다(Checklist 쪽에서 시작하면 시작 안 한
    // 매물이 원천적으로 빠짐). 정렬 기준(최종 점검일 = 체크리스트가 있으면 그 updatedAt, 없으면 매물
    // 자체의 updatedAt)은 사용자가 고르는 값이 아니라 항상 고정이라 쿼리에 직접 명시한다 - 호출부는
    // Pageable에 정렬을 담아 넘기면 안 되고 page/size만 담은 Pageable을 넘겨야 한다(그렇지 않으면
    // 이 ORDER BY 뒤에 추가 정렬이 덧붙어 의도와 다른 결과가 나올 수 있음).
    // property 도메인이 checklist를 몰라도 되도록(기존 컨벤션), 이 JOIN 쿼리는 property 쪽이 아니라
    // 여기(checklist 쪽, 이미 Property를 참조하는 도메인)에 둔다.
    // p.address도 LEFT JOIN FETCH로 같이 가져온다 - PropertyAddress는 mappedBy(비소유) 쪽 @OneToOne이라
    // Hibernate가 지연 로딩용 프록시 자체를 못 만들어 default_batch_fetch_size로도 안 묶이고 매물마다
    // 개별 SELECT가 나가는 게 실측으로 확인됨(ChecklistOverviewResponse.from()이 매물마다
    // property.getAddress()를 호출). 1:1 관계라 fetch join을 페이지네이션과 같이 써도 row가 안
    // 불어나 안전함(items 같은 컬렉션 fetch join과는 다름).
    @Query("""
            SELECT p, c FROM Property p LEFT JOIN FETCH p.address LEFT JOIN Checklist c ON c.property = p AND c.user.id = :userId
            WHERE p.userId = :userId AND p.status = :status
            ORDER BY COALESCE(c.updatedAt, p.updatedAt) DESC
            """)
    Page<Object[]> findOverviewByUserId(
            @Param("userId") Long userId,
            @Param("status") PropertyStatus status,
            Pageable pageable
    );
}
