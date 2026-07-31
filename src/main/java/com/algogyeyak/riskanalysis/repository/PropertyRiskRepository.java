package com.algogyeyak.riskanalysis.repository;

import com.algogyeyak.riskanalysis.entity.PropertyRisk;
import com.algogyeyak.riskanalysis.enums.RiskSignalType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PropertyRiskRepository extends JpaRepository<PropertyRisk, Long> {
    Optional<PropertyRisk> findByPropertyIdAndSignalType(Long propertyId, RiskSignalType signalType);
    List<PropertyRisk> findAllByPropertyId(Long propertyId);
    void deleteByPropertyIdAndSignalType(Long propertyId, RiskSignalType signalType); // 신호가 더 이상 감지되지 않을 때 삭제
}
