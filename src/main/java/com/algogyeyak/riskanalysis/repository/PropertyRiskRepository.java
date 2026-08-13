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

    /**
     * 매물 목록 카드에 "확인 필요 신호" 요약(개수 + 설명)을 붙이기 위한 조회. checklistProgress와
     * 달리 여기서는 DB에서 GROUP BY로 집계하지 않고 원본 행을 전부 가져와 서비스 레이어에서
     * propertyId별로 묶는다 - signalSummary가 description들을 문장으로 이어붙인 문자열이라
     * GROUP_CONCAT류의 DB별 문법 차이(H2/MySQL) 없이 portable하게 처리하기 위함이다. 유저가
     * 가진 매물 수가 많지 않은 개인용 목록이라 이 방식으로도 N+1 없이 쿼리 1회면 충분하다.
     */
    List<PropertyRisk> findAllByProperty_UserId(Long userId);
}
