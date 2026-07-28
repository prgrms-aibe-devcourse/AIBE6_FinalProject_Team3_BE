package com.algogyeyak.riskanalysis.repository;

import com.algogyeyak.riskanalysis.entity.PropertyRisk;
import com.algogyeyak.riskanalysis.enums.RiskSignalType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PropertyRiskRepository extends JpaRepository<PropertyRisk, Long> {
    List<PropertyRisk> findByPropertyIdOrderByDetectedAtDesc(Long propertyId);
    void deleteByPropertyIdAndSignalType(Long propertyId, RiskSignalType signalType); // 신호별 재계산 시 해당 신호 결과만 삭제
}
