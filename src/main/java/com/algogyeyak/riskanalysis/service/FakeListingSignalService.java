package com.algogyeyak.riskanalysis.service;

import com.algogyeyak.global.error.ErrorCode;
import com.algogyeyak.global.exception.BusinessException;
import com.algogyeyak.property.entity.Property;
import com.algogyeyak.property.repository.PropertyRepository;
import com.algogyeyak.riskanalysis.client.MarketDataClient;
import com.algogyeyak.riskanalysis.dto.MarketComparison;
import com.algogyeyak.riskanalysis.dto.RiskAnalysisSummaryResponse;
import com.algogyeyak.riskanalysis.dto.RiskSignalListResponse;
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
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@Slf4j
public class FakeListingSignalService {
    private final List<SignalDetector> detectors;
    private final MarketDataClient marketDataClient;
    private final PropertyRiskCheckRepository riskCheckRepository;
    private final PropertyRiskRepository riskRepository;
    private final PropertyRepository propertyRepository;
    private final DepositSafetyCheckService depositSafetyCheckService;
    private final RiskPolicyConfig policyConfig;
    private final TransactionTemplate requiresNewTransactionTemplate;

    public FakeListingSignalService(
            List<SignalDetector> detectors,
            MarketDataClient marketDataClient,
            PropertyRiskCheckRepository riskCheckRepository,
            PropertyRiskRepository riskRepository,
            PropertyRepository propertyRepository,
            DepositSafetyCheckService depositSafetyCheckService,
            RiskPolicyConfig policyConfig,
            PlatformTransactionManager transactionManager) {
        this.detectors = detectors;
        this.marketDataClient = marketDataClient;
        this.riskCheckRepository = riskCheckRepository;
        this.riskRepository = riskRepository;
        this.propertyRepository = propertyRepository;
        this.depositSafetyCheckService = depositSafetyCheckService;
        this.policyConfig = policyConfig;
        this.requiresNewTransactionTemplate = new TransactionTemplate(transactionManager);
        this.requiresNewTransactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * 매물의 신호 4종 현재 상태를 조회한다. checklist 도메인과 동일한 패턴으로 매물 존재/삭제/소유권을
     * 확인한 뒤, PropertyRiskCheck(신호별 상태)와 PropertyRisk(리스크 발견 시 설명)를 signalType
     * 기준으로 묶어 응답한다.
     */
    public RiskSignalListResponse getSignals(Long userId, Long propertyId) {
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

        List<RiskSignalResponse> signals = riskCheckRepository.findAllByPropertyId(propertyId).stream()
                .map(check -> RiskSignalResponse.from(check, risksByType.get(check.getSignalType())))
                .toList();
        int signalCount = (int) signals.stream().filter(signal -> signal.description() != null).count();

        return new RiskSignalListResponse(propertyId, signalCount, signals, RiskSignalListResponse.DISCLAIMER);
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
        RiskSignalListResponse signals = getSignals(userId, propertyId);
        return new RiskAnalysisSummaryResponse(propertyId, signals.signalCount(), policyConfig.getVersion(), LocalDateTime.now());
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

        depositSafetyCheckService.checkAndSave(property);
    }

    private void checkAndSaveSignal(Property property, MarketComparison comparison, SignalDetector detector) {
        RiskSignalType signalType = detector.type();
        SignalCheckResult result = detector.detect(property, comparison);

        upsertCheck(property, signalType, result.status(), result.reason());
        upsertRisk(property, signalType, result);
    }

    // "없으면 insert, 있으면 update"인데 조회와 insert 사이에 갭이 있어, 같은 매물에 대한 두 요청이
    // 동시에 들어오면(예: React 개발 모드가 useEffect를 두 번 실행하는 경우, 사용자 요청과
    // RiskRecalculationService의 배치 재계산이 겹치는 경우) 둘 다 "기존 행 없음"을 보고 동시에 insert를
    // 시도해 (property_id, signal_type) 유니크 제약을 위반할 수 있다(DataIntegrityViolationException).
    // insert를 REQUIRES_NEW로 격리해서, 위반이 나도 그 임시 트랜잭션의 세션만 버려지고 이 메서드가
    // 속한 바깥 트랜잭션의 세션은 정상 상태로 남게 한다 - 같은 세션에서 saveAndFlush가 유니크 제약
    // 위반으로 실패한 뒤 그 세션으로 쿼리를 이어가면 Hibernate가 "세션이 예외 이후 flush됨
    // (AssertionFailure)"을 던지는 문제를 피하기 위함(CustomOAuth2UserService.createUser()와 동일한
    // 이유·동일한 패턴). 실패하면 그 사이 먼저 커밋된 행을 재조회해서 덮어쓴다.
    private void upsertCheck(Property property, RiskSignalType signalType, RiskCheckStatus status, RiskCheckReason reason) {
        Optional<PropertyRiskCheck> existing = riskCheckRepository.findByPropertyIdAndSignalType(property.getId(), signalType);
        if (existing.isPresent()) {
            existing.get().overwrite(status, reason, policyConfig.getVersion());
            return;
        }

        PropertyRiskCheck newCheck = switch (status) {
            case SUCCESS -> PropertyRiskCheck.success(property, signalType, policyConfig.getVersion());
            case UNDETERMINABLE -> PropertyRiskCheck.undeterminable(property, signalType, reason, policyConfig.getVersion());
            case FAILED -> PropertyRiskCheck.failed(property, signalType, reason, policyConfig.getVersion());
        };

        try {
            requiresNewTransactionTemplate.executeWithoutResult(status2 -> riskCheckRepository.saveAndFlush(newCheck));
        } catch (DataIntegrityViolationException e) {
            // 재조회도 REQUIRES_NEW로 새 트랜잭션에서 한다 - 바깥(이 메서드가 속한) 트랜잭션에서 그대로
            // 재조회하면, MySQL InnoDB의 기본 격리수준(REPEATABLE READ)에서는 그 트랜잭션이 이미 앞서
            // 읽은 시점의 스냅샷에 갇혀 있어서 방금 다른 트랜잭션이 커밋한 승자 행이 안 보일 수 있다
            // (H2는 기본이 READ_COMMITTED라 로컬 테스트에서는 이 문제가 드러나지 않는다). 새
            // 트랜잭션은 항상 새 스냅샷을 잡으므로 격리수준과 무관하게 승자를 확실히 볼 수 있다.
            boolean recovered = Boolean.TRUE.equals(requiresNewTransactionTemplate.execute(status2 ->
                    riskCheckRepository.findByPropertyIdAndSignalType(property.getId(), signalType)
                            .map(winner -> {
                                winner.overwrite(status, reason, policyConfig.getVersion());
                                riskCheckRepository.saveAndFlush(winner);
                                return true;
                            })
                            .orElse(false)));
            if (!recovered) {
                log.error("PropertyRiskCheck 동시 insert 경쟁 복구 실패 - propertyId={}, signalType={}",
                        property.getId(), signalType, e);
                throw e;
            }
        }
    }

    // status=SUCCESS이고 설명이 있는 경우에만 리스크 1건을 upsert하고, 그 외(신호 해소·판정불가·실패)에는
    // 이전에 저장해둔 리스크가 있다면 지운다 — property_risks는 (property_id, signal_type)당 최신 판정
    // 결과 1건만 유지한다. insert 경쟁 대비는 upsertCheck()와 동일한 이유·동일한 패턴.
    private void upsertRisk(Property property, RiskSignalType signalType, SignalCheckResult result) {
        if (result.status() != RiskCheckStatus.SUCCESS || result.description() == null) {
            riskRepository.deleteByPropertyIdAndSignalType(property.getId(), signalType);
            return;
        }

        Optional<PropertyRisk> existing = riskRepository.findByPropertyIdAndSignalType(property.getId(), signalType);
        if (existing.isPresent()) {
            existing.get().overwrite(result.description());
            return;
        }

        PropertyRisk newRisk = PropertyRisk.of(property, signalType, result.description());
        try {
            requiresNewTransactionTemplate.executeWithoutResult(status -> riskRepository.saveAndFlush(newRisk));
        } catch (DataIntegrityViolationException e) {
            // upsertCheck()와 동일한 이유로 재조회도 REQUIRES_NEW 새 트랜잭션에서 한다.
            boolean recovered = Boolean.TRUE.equals(requiresNewTransactionTemplate.execute(status ->
                    riskRepository.findByPropertyIdAndSignalType(property.getId(), signalType)
                            .map(winner -> {
                                winner.overwrite(result.description());
                                riskRepository.saveAndFlush(winner);
                                return true;
                            })
                            .orElse(false)));
            if (!recovered) {
                log.error("PropertyRisk 동시 insert 경쟁 복구 실패 - propertyId={}, signalType={}",
                        property.getId(), signalType, e);
                throw e;
            }
        }
    }
}
