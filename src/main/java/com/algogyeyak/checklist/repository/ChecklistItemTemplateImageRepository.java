package com.algogyeyak.checklist.repository;

import com.algogyeyak.checklist.entity.ChecklistItemTemplateImage;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChecklistItemTemplateImageRepository extends JpaRepository<ChecklistItemTemplateImage, Long> {

    List<ChecklistItemTemplateImage> findByTemplateIdOrderByDisplayOrderAsc(Long templateId);

    // AdminChecklistTemplateService.delete()가 템플릿 삭제 전에 호출한다 - template_id는
    // nullable=false FK라(ChecklistItemTemplateImage 참고) cascade/orphanRemoval 없이 템플릿만
    // 지우면 이미지가 있는 템플릿에서 FK 제약 위반(DataIntegrityViolationException, 처리되지
    // 않아 500)이 난다.
    void deleteByTemplateId(Long templateId);
}
