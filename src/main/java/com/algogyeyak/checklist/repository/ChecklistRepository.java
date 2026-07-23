package com.algogyeyak.checklist.repository;

import com.algogyeyak.checklist.entity.Checklist;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ChecklistRepository extends JpaRepository<Checklist, Long> {

    // 유저-매물 조합당 활성 체크리스트는 1개뿐이므로, 생성 요청이 멱등인지 확인할 때 사용한다.
    Optional<Checklist> findByUserIdAndPropertyId(Long userId, Long propertyId);
}
