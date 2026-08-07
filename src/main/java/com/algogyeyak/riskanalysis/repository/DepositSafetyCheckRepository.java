package com.algogyeyak.riskanalysis.repository;

import com.algogyeyak.riskanalysis.entity.DepositSafetyCheck;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DepositSafetyCheckRepository extends JpaRepository<DepositSafetyCheck, Long> {
    Optional<DepositSafetyCheck> findByPropertyId(Long propertyId);

    /**
     * 매물 목록 카드에 전세가율 배지를 붙이기 위한 조회. PropertyRiskRepository.findAllByProperty_UserId와
     * 동일한 이유로 원본 행을 가져와 서비스 레이어에서 propertyId별로 묶는다 - status=CALCULATED가
     * 아닌 행은 jeonseRatio 자체가 null이라 서비스에서 걸러낸다.
     */
    List<DepositSafetyCheck> findAllByProperty_UserId(Long userId);
}
