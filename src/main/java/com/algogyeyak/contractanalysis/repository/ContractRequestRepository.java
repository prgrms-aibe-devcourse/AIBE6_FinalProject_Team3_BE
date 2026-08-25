package com.algogyeyak.contractanalysis.repository;

import com.algogyeyak.contractanalysis.entity.ContractRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ContractRequestRepository extends JpaRepository<ContractRequest, Long> {

    Page<ContractRequest> findAllByUserId(Long userId, Pageable pageable);
}
