package com.algogyeyak.riskanalysis.client;

import com.algogyeyak.riskanalysis.dto.PropertyRiskSummary;
import com.algogyeyak.riskanalysis.entity.PropertyRisk;
import com.algogyeyak.riskanalysis.entity.PropertyRiskCheck;
import com.algogyeyak.riskanalysis.enums.DepositSafetyStatus;
import com.algogyeyak.riskanalysis.repository.DepositSafetyCheckRepository;
import com.algogyeyak.riskanalysis.repository.PropertyRiskCheckRepository;
import com.algogyeyak.riskanalysis.repository.PropertyRiskRepository;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PropertyRiskSummaryProviderImpl implements PropertyRiskSummaryProvider {

    private final PropertyRiskRepository propertyRiskRepository;
    private final PropertyRiskCheckRepository propertyRiskCheckRepository;
    private final DepositSafetyCheckRepository depositSafetyCheckRepository;

    @Override
    public Map<Long, PropertyRiskSummary> getSummariesByUserId(Long userId) {
        // risk-analysis를 한 번도 안 돌린 매물(checkSignalCount=null)과 돌렸는데 신호가 0건인
        // 매물(checkSignalCount=0)을 구분하기 위해, "실행 여부"는 PropertyRiskCheck 쪽으로 판단하고
        // "실제 발견된 신호"는 PropertyRisk 쪽으로 집계한다 (FakeListingSignalService 참고).
        Set<Long> riskCheckedPropertyIds = propertyRiskCheckRepository.findAllByProperty_UserId(userId)
                .stream()
                .map(check -> check.getProperty().getId())
                .collect(Collectors.toSet());

        Map<Long, List<PropertyRisk>> risksByPropertyId = propertyRiskRepository.findAllByProperty_UserId(userId)
                .stream()
                .collect(Collectors.groupingBy(risk -> risk.getProperty().getId()));

        Map<Long, Integer> jeonseRatioByPropertyId = depositSafetyCheckRepository.findAllByProperty_UserId(userId)
                .stream()
                .filter(check -> check.getStatus() == DepositSafetyStatus.CALCULATED)
                .collect(Collectors.toMap(
                        check -> check.getProperty().getId(),
                        check -> check.getJeonseRatio().intValue()
                ));

        Set<Long> propertyIds = new HashSet<>(riskCheckedPropertyIds);
        propertyIds.addAll(jeonseRatioByPropertyId.keySet());

        Map<Long, PropertyRiskSummary> summaries = new HashMap<>();
        for (Long propertyId : propertyIds) {
            Integer checkSignalCount = riskCheckedPropertyIds.contains(propertyId)
                    ? risksByPropertyId.getOrDefault(propertyId, List.of()).size()
                    : null;
            String signalSummary = checkSignalCount != null && checkSignalCount > 0
                    ? risksByPropertyId.get(propertyId).stream()
                            .map(PropertyRisk::getDescription)
                            .collect(Collectors.joining(", "))
                    : null;
            summaries.put(propertyId, new PropertyRiskSummary(checkSignalCount, signalSummary, jeonseRatioByPropertyId.get(propertyId)));
        }
        return summaries;
    }
}
