package com.algogyeyak.riskanalysis.service;

import com.algogyeyak.global.error.ErrorCode;
import com.algogyeyak.global.exception.BusinessException;
import com.algogyeyak.property.entity.Property;
import com.algogyeyak.property.repository.PropertyRepository;
import com.algogyeyak.riskanalysis.client.MarketDataClient;
import com.algogyeyak.riskanalysis.dto.MarketComparison;
import com.algogyeyak.riskanalysis.dto.RiskAnalysisSummaryResponse;
import com.algogyeyak.riskanalysis.dto.RiskSignalResponse;
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

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class FakeListingSignalService {
    private final List<SignalDetector> detectors;
    private final MarketDataClient marketDataClient;
    private final PropertyRiskCheckRepository riskCheckRepository;
    private final PropertyRiskRepository riskRepository;
    private final PropertyRepository propertyRepository;
    private final RiskPolicyConfig policyConfig;

    /**
     * 매물의 신호 4종 현재 상태를 조회한다. checklist 도메인과 동일한 패턴으로 매물 존재/삭제/소유권을
     * 확인한 뒤, PropertyRiskCheck(신호별 상태)와 PropertyRisk(리스크 발견 시 설명)를 signalType
     * 기준으로 묶어 응답한다.
     */
    public List<RiskSignalResponse> getSignals(Long userId, Long propertyId) {
        Property property = propertyRepository.findById(propertyId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PROPERTY_NOT_FOUND));
        if (property.isDeleted()) {
            throw new BusinessException(ErrorCode.PROPERTY_NOT_FOUND);
        }
        if (!property.isOwnedBy(userId)) {
            throw new BusinessException(ErrorCode.PROPERTY_ACCESS_DENIED);
        }

        Map<RiskSignalType, PropertyRisk> risksByType = riskRepository.findAllByPropertyId(propertyId).stream()
                .collect(Collectors.toMap(PropertyRisk::getSignalType, Function.identity()));

        return riskCheckRepository.findAllByPropertyId(propertyId).stream()
                .map(check -> RiskSignalResponse.from(check, risksByType.get(check.getSignalType())))
                .toList();
    }

    /**
     * checkAndSave(Property)와 동일하지만, 컨트롤러에서 호출하기 위해 매물 존재/삭제/소유권을 먼저
     * 확인한다(getSignals()와 동일한 패턴) - 최초 실행과 재계산 모두 이 메서드 하나로 처리된다
     * (checkAndSave(Property)가 이미 upsert 구조라 있으면 덮어쓰고 없으면 새로 만들기 때문에
     * "최초 실행"과 "재계산"을 구분할 이유가 없다).
     */
    @Transactional
    public void checkAndSave(Long userId, Long propertyId) {
        Property property = propertyRepository.findById(propertyId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PROPERTY_NOT_FOUND));
        if (property.isDeleted()) {
            throw new BusinessException(ErrorCode.PROPERTY_NOT_FOUND);
        }
        if (!property.isOwnedBy(userId)) {
            throw new BusinessException(ErrorCode.PROPERTY_ACCESS_DENIED);
        }

        checkAndSave(property);
    }

    /**
     * checkAndSave(userId, propertyId)를 실행한 뒤, 상세 목록(GET /risk-signals가 담당) 대신
     * "리스크가 실제로 발견된 신호가 몇 건인지" 요약만 반환한다 - POST 응답이 너무 무거워지지 않게
     * 상세는 별도 GET 호출로 분리하는 설계.
     */
    @Transactional
    public RiskAnalysisSummaryResponse checkAndSummarize(Long userId, Long propertyId) {
        checkAndSave(userId, propertyId);
        List<RiskSignalResponse> signals = getSignals(userId, propertyId);
        int signalCount = (int) signals.stream().filter(signal -> signal.description() != null).count();
        return new RiskAnalysisSummaryResponse(signalCount, policyConfig.getVersion(), LocalDateTime.now());
    }

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

        upsertCheck(property, signalType, result.status(), result.reason());
        upsertRisk(property, signalType, result);
    }

    private void upsertCheck(Property property, RiskSignalType signalType, RiskCheckStatus status, RiskCheckReason reason) {
        PropertyRiskCheck check = riskCheckRepository.findByPropertyIdAndSignalType(property.getId(), signalType)
                .orElse(null);

        if (check == null) {
            PropertyRiskCheck newCheck = switch (status) {
                case SUCCESS -> PropertyRiskCheck.success(property, signalType, policyConfig.getVersion());
                case UNDETERMINABLE -> PropertyRiskCheck.undeterminable(property, signalType, reason, policyConfig.getVersion());
                case FAILED -> PropertyRiskCheck.failed(property, signalType, reason, policyConfig.getVersion());
            };
            riskCheckRepository.save(newCheck);
            return;
        }

        check.overwrite(status, reason, policyConfig.getVersion());
    }

    // status=SUCCESS이고 설명이 있는 경우에만 리스크 1건을 upsert하고, 그 외(신호 해소·판정불가·실패)에는
    // 이전에 저장해둔 리스크가 있다면 지운다 — property_risks는 (property_id, signal_type)당 최신 판정
    // 결과 1건만 유지한다.
    private void upsertRisk(Property property, RiskSignalType signalType, SignalCheckResult result) {
        if (result.status() != RiskCheckStatus.SUCCESS || result.description() == null) {
            riskRepository.deleteByPropertyIdAndSignalType(property.getId(), signalType);
            return;
        }

        riskRepository.findByPropertyIdAndSignalType(property.getId(), signalType)
                .ifPresentOrElse(
                        risk -> risk.overwrite(result.description()),
                        () -> riskRepository.save(PropertyRisk.of(property, signalType, result.description()))
                );
    }
}
