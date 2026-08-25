package com.algogyeyak.contractanalysis.repository;

import com.algogyeyak.contractanalysis.entity.ContractClause;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ContractClauseRepository extends JpaRepository<ContractClause, Long> {
}
