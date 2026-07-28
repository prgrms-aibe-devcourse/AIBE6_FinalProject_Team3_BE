package com.algogyeyak.riskanalysis.repository;

import com.algogyeyak.riskanalysis.entity.PropertyRisk;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PropertyRiskRepository extends JpaRepository<PropertyRisk, Long> {
    List<PropertyRisk> findByPropertyIdOrderByDetectedAtDesc(Long propertyId);
    void deleteByPropertyId(Long propertyId); // 재계산 시 기존 신호 전체 삭제용
}
