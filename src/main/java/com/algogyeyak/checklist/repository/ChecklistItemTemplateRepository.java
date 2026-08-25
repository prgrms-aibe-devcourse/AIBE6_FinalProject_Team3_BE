package com.algogyeyak.checklist.repository;

import com.algogyeyak.checklist.entity.ChecklistItemCode;
import com.algogyeyak.checklist.entity.ChecklistItemTemplate;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface ChecklistItemTemplateRepository extends JpaRepository<ChecklistItemTemplate, Long> {

    // 템플릿 버전이 올라가면 이전 버전 행은 active=false로 내려가므로, 항상 "현재 활성" 문항만 조회하면 된다.
    // images까지 즉시 로딩한다 - ChecklistService.createChecklist()가 이 결과로 새 ChecklistItem을
    // 만들 때 item.template을 이 엔티티로 그대로 채워 넣는데, REQUIRES_NEW 복구 경쟁에서 진 요청이
    // 재조회한 Checklist를 반환하는 경로와 별개로, 새로 생성(승자) 경로도 OSIV 없이 호출되면(테스트
    // 환경 등) 세션이 닫힌 뒤 template.getImages() 접근 시 LazyInitializationException이 난다
    // (ChecklistServiceConcurrencyTest로 재현 확인, 2026-08-24). findAllWithImages()와 동일한 이유.
    @EntityGraph(attributePaths = "images")
    List<ChecklistItemTemplate> findByActiveTrueOrderByDisplayOrderAsc();

    // 관리자 페이지는 비활성(active=false) 문항도 함께 보여줘야 관리할 수 있으므로 전체를 조회한다.
    List<ChecklistItemTemplate> findAllByOrderByDisplayOrderAsc();

    // 관리자가 같은 code를 여러 활성 문항에 중복 배정하지 않았는지 검증하기 위한 조회
    // (AdminChecklistTemplateService 참고 - 자동 issueFound 판정이 code당 정확히 1개를 전제한다).
    List<ChecklistItemTemplate> findByCodeAndActiveTrue(ChecklistItemCode code);

    // ChecklistTemplateSeeder 전용. 시더는 각 문항을 독립된(개별) 트랜잭션으로 저장/갱신해 동시
    // 기동 인스턴스 간 삽입 충돌을 격리한다(클래스 주석 참고) - 즉 이 메서드가 반환한 엔티티는
    // 메서드 호출이 끝나면 이미 detached 상태다. images는 LAZY라 그 뒤에 접근하면
    // LazyInitializationException이 나므로, 여기서 미리 즉시 로딩해 반환한다.
    @Query("SELECT DISTINCT t FROM ChecklistItemTemplate t LEFT JOIN FETCH t.images")
    List<ChecklistItemTemplate> findAllWithImages();
}
