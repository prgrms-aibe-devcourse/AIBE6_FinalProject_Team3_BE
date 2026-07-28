package com.algogyeyak.riskanalysis.repository;

import com.algogyeyak.riskanalysis.entity.PropertyRiskCheck;
import com.algogyeyak.riskanalysis.enums.RiskSignalType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PropertyRiskCheckRepository extends JpaRepository<PropertyRiskCheck, Long> {
    Optional<PropertyRiskCheck> findByPropertyIdAndSignalType(Long propertyId, RiskSignalType signalType);
    List<PropertyRiskCheck> findAllByPropertyId(Long propertyId);
}
