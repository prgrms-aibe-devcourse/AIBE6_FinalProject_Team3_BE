package com.algogyeyak.riskanalysis.service;

import com.algogyeyak.property.entity.Property;
import com.algogyeyak.riskanalysis.client.MarketDataClient;
import com.algogyeyak.riskanalysis.dto.DetectedSignal;
import com.algogyeyak.riskanalysis.dto.MarketComparison;
import com.algogyeyak.riskanalysis.enums.MarketComparisonStatus;
import com.algogyeyak.riskanalysis.entity.PropertyRisk;
import com.algogyeyak.riskanalysis.entity.PropertyRiskCheck;
import com.algogyeyak.riskanalysis.enums.RiskCheckReason;
import com.algogyeyak.riskanalysis.enums.RiskCheckStatus;
import com.algogyeyak.riskanalysis.policy.RiskPolicyConfig;
import com.algogyeyak.riskanalysis.repository.PropertyRiskCheckRepository;
import com.algogyeyak.riskanalysis.repository.PropertyRiskRepository;
import com.algogyeyak.riskanalysis.signal.SignalDetector;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class FakeListingSignalService {
    private final List<SignalDetector> detectors;
    private final MarketDataClient marketDataClient;
    private final PropertyRiskCheckRepository riskCheckRepository;
    private final PropertyRiskRepository riskRepository;
    private final RiskPolicyConfig policyConfig;

    @Transactional
    public void checkAndSave(Property property) {
        MarketComparison comparison = marketDataClient.getComparison(property.getId())
                .orElse(null);

        if (comparison == null || comparison.status() == MarketComparisonStatus.FAILED) {
            upsertCheck(property.getId(), RiskCheckStatus.FAILED, RiskCheckReason.DATA_FETCH_FAILURE);
            return;
        }
        if (comparison.status() == MarketComparisonStatus.UNDETERMINABLE) {
            upsertCheck(property.getId(), RiskCheckStatus.UNDETERMINABLE, RiskCheckReason.NO_COMPARABLE_TRANSACTION);
            return;
        }

        // SUCCESS
        Long checkId = upsertCheck(property.getId(), RiskCheckStatus.SUCCESS, null);

        List<DetectedSignal> detected = detectors.stream()
                .filter(SignalDetector::isEnabled)
                .flatMap(d -> d.detect(property, comparison).stream())
                .toList();

        // DTO → 엔티티 조립은 여기서만 담당
        List<PropertyRisk> entities = detected.stream()
                .map(signal -> PropertyRisk.of(
                        checkId,
                        property.getId(),
                        signal.signalType(),
                        signal.description()
                ))
                .toList();

        riskRepository.deleteByPropertyId(property.getId());
        riskRepository.saveAll(entities);
    }

    private Long upsertCheck(Long propertyId, RiskCheckStatus status, RiskCheckReason reason) {
        PropertyRiskCheck check = riskCheckRepository.findByPropertyId(propertyId)
                .orElse(null);

        if (check == null) {
            PropertyRiskCheck newCheck = switch (status) {
                case SUCCESS -> PropertyRiskCheck.success(propertyId, policyConfig.getVersion());
                case UNDETERMINABLE -> PropertyRiskCheck.undeterminable(propertyId, reason, policyConfig.getVersion());
                case FAILED -> PropertyRiskCheck.failed(propertyId, reason, policyConfig.getVersion());
            };
            return riskCheckRepository.save(newCheck).getId();
        }

        check.overwrite(status, reason, policyConfig.getVersion());
        return check.getId();
    }
}
