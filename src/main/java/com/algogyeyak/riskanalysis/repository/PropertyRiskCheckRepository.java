package com.algogyeyak.riskanalysis.repository;

import com.algogyeyak.riskanalysis.entity.PropertyRiskCheck;
import com.algogyeyak.riskanalysis.enums.RiskSignalType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PropertyRiskCheckRepository extends JpaRepository<PropertyRiskCheck, Long> {
    Optional<PropertyRiskCheck> findByPropertyIdAndSignalType(Long propertyId, RiskSignalType signalType);
    List<PropertyRiskCheck> findAllByPropertyId(Long propertyId);

    /**
     * 매물 목록에서 "아직 risk-analysis를 한 번도 안 돌린 매물"(checkSignalCount=null)과
     * "돌렸는데 발견된 신호가 0건인 매물"(checkSignalCount=0)을 구분하기 위한 조회. PropertyRisk는
     * 리스크가 실제로 발견된 신호만 저장하므로(PropertyRisk 자체가 비어있는 것만으로는 두 경우를
     * 구분할 수 없음), 신호 4종을 판정할 때마다 항상 upsert되는 PropertyRiskCheck 쪽에 행이
     * 있는지로 "실행 여부"를 판단한다.
     */
    List<PropertyRiskCheck> findAllByProperty_UserId(Long userId);
}
