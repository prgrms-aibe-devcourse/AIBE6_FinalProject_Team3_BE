package com.algogyeyak.checklist.repository;

import com.algogyeyak.checklist.entity.Checklist;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChecklistRepository extends JpaRepository<Checklist, Long> {

    // 유저-매물 조합당 활성 체크리스트는 1개뿐이므로, 생성 요청이 멱등인지 확인할 때 사용한다.
    Optional<Checklist> findByUserIdAndPropertyId(Long userId, Long propertyId);

    // 매물 하나당 체크리스트는(소유자만 만들 수 있어) 최대 1개뿐이라, propertyId만으로 조회해도 안전하다.
    // getChecklist()에서 "체크리스트 없음(404)"과 "본인 소유 아님(403)"을 구분하기 위해 사용한다.
    Optional<Checklist> findByPropertyId(Long propertyId);

    // "내 체크리스트 목록"(GET /checklists) 조회에서, 유저의 매물 목록과 매칭하기 위해 한 번에 가져온다.
    List<Checklist> findAllByUserId(Long userId);
}
