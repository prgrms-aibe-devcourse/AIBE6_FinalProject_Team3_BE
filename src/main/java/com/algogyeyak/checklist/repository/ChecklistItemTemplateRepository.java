package com.algogyeyak.checklist.repository;

import com.algogyeyak.checklist.entity.ChecklistItemTemplate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ChecklistItemTemplateRepository extends JpaRepository<ChecklistItemTemplate, Long> {

    // 템플릿 버전이 올라가면 이전 버전 행은 active=false로 내려가므로, 항상 "현재 활성" 문항만 조회하면 된다.
    List<ChecklistItemTemplate> findByActiveTrueOrderByDisplayOrderAsc();
}
