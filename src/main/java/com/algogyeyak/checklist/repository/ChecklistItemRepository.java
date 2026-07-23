package com.algogyeyak.checklist.repository;

import com.algogyeyak.checklist.entity.ChecklistItem;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChecklistItemRepository extends JpaRepository<ChecklistItem, Long> {
}
