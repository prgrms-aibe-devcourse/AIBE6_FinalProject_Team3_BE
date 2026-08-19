package com.algogyeyak.checklist.repository;

import com.algogyeyak.checklist.entity.ChecklistItemTemplateImage;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChecklistItemTemplateImageRepository extends JpaRepository<ChecklistItemTemplateImage, Long> {

    List<ChecklistItemTemplateImage> findByTemplateIdOrderByDisplayOrderAsc(Long templateId);
}
