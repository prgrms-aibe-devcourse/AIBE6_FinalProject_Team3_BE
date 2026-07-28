package com.algogyeyak.riskanalysis.repository;

import com.algogyeyak.riskanalysis.entity.PropertyRiskCheck;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PropertyRiskCheckRepository extends JpaRepository<PropertyRiskCheck, Long> {
    Optional<PropertyRiskCheck> findByPropertyId(Long propertyId);
}
