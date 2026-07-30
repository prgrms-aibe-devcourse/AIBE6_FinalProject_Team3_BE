package com.algogyeyak.riskanalysis.service;

import com.algogyeyak.property.entity.Property;
import com.algogyeyak.riskanalysis.client.MarketDataClient;
import com.algogyeyak.riskanalysis.dto.MarketComparison;
import com.algogyeyak.riskanalysis.dto.SignalCheckResult;
import com.algogyeyak.riskanalysis.entity.PropertyRisk;
import com.algogyeyak.riskanalysis.entity.PropertyRiskCheck;
import com.algogyeyak.riskanalysis.enums.RiskCheckReason;
import com.algogyeyak.riskanalysis.enums.RiskCheckStatus;
import com.algogyeyak.riskanalysis.enums.RiskSignalType;
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

    /**
     * 신호 4종을 각각 독립적으로 판정·저장한다. 시세비교(comparison)를 한 번만 조회해 각 탐지기에
     * 넘기되, 그 결과를 어떻게 쓸지는(혹은 아예 무시할지는) 각 SignalDetector가 결정한다 —
     * 시세비교가 실패/판정불가여도 이를 필요로 하지 않는 신호(중복매물/동일계정/재등록)는
     * 자체적으로 판정을 계속 시도한다.
     */
    @Transactional
    public void checkAndSave(Property property) {
        MarketComparison comparison = marketDataClient.getComparison(property.getId())
                .orElse(null);

        detectors.stream()
                .filter(SignalDetector::isEnabled)
                .forEach(detector -> checkAndSaveSignal(property, comparison, detector));
    }

    private void checkAndSaveSignal(Property property, MarketComparison comparison, SignalDetector detector) {
        RiskSignalType signalType = detector.type();
        SignalCheckResult result = detector.detect(property, comparison);

        Long checkId = upsertCheck(property.getId(), signalType, result.status(), result.reason());

        riskRepository.deleteByPropertyIdAndSignalType(property.getId(), signalType);

        if (result.status() == RiskCheckStatus.SUCCESS && !result.detectedSignals().isEmpty()) {
            List<PropertyRisk> entities = result.detectedSignals().stream()
                    .map(signal -> PropertyRisk.of(checkId, property.getId(), signalType, signal.description()))
                    .toList();
            riskRepository.saveAll(entities);
        }
    }

    private Long upsertCheck(Long propertyId, RiskSignalType signalType, RiskCheckStatus status, RiskCheckReason reason) {
        PropertyRiskCheck check = riskCheckRepository.findByPropertyIdAndSignalType(propertyId, signalType)
                .orElse(null);

        if (check == null) {
            PropertyRiskCheck newCheck = switch (status) {
                case SUCCESS -> PropertyRiskCheck.success(propertyId, signalType, policyConfig.getVersion());
                case UNDETERMINABLE -> PropertyRiskCheck.undeterminable(propertyId, signalType, reason, policyConfig.getVersion());
                case FAILED -> PropertyRiskCheck.failed(propertyId, signalType, reason, policyConfig.getVersion());
            };
            return riskCheckRepository.save(newCheck).getId();
        }

        check.overwrite(status, reason, policyConfig.getVersion());
        return check.getId();
    }
}
