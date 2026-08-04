package com.algogyeyak.riskanalysis.repository;

import com.algogyeyak.riskanalysis.entity.DepositSafetyCheck;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DepositSafetyCheckRepository extends JpaRepository<DepositSafetyCheck, Long> {
    Optional<DepositSafetyCheck> findByPropertyId(Long propertyId);
}
