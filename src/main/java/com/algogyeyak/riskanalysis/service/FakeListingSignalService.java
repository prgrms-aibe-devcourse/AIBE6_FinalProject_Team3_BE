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
import org.springframework.orm.ObjectOptimisticLockingFailureException;
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
     * "최초 실행"과 "재계산"을 구분할 이유가 없다). 반환값(리스크가 발견된 신호 수)은
     * checkAndSummarize()가 사용한다 - 이유는 그 메서드의 주석 참고.
     */
    @Transactional
    public int checkAndSave(Long userId, Long propertyId) {
        Property property = propertyRepository.findById(propertyId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PROPERTY_NOT_FOUND));
        if (property.isDeleted()) {
            throw new BusinessException(ErrorCode.PROPERTY_NOT_FOUND);
        }
        if (!property.isOwnedBy(userId)) {
            throw new BusinessException(ErrorCode.PROPERTY_ACCESS_DENIED);
        }

        return checkAndSave(property);
    }

    /**
     * checkAndSave(userId, propertyId)를 실행한 뒤, 상세 목록(GET /risk-signals가 담당) 대신
     * "리스크가 실제로 발견된 신호가 몇 건인지" 요약만 반환한다 - POST 응답이 너무 무거워지지 않게
     * 상세는 별도 GET 호출로 분리하는 설계. checkAndSave()가 반환한 개수를 그대로 쓰고, 저장 직후
     * getSignals()로 DB를 다시 조회하지 않는다 - checkAndSave() 안에서 신규로 발견된 신호는
     * PropertyRiskCheck/PropertyRisk에 REQUIRES_NEW(별도 트랜잭션)로 insert되는데, MySQL의 기본
     * 격리수준(REPEATABLE READ)에서는 이 메서드가 속한 바깥 트랜잭션이 이미 그 전에(예: 매물 조회
     * 시점) 스냅샷을 고정해뒀다면 방금 다른 트랜잭션이 커밋한 그 행이 같은 트랜잭션의 이후 조회에서
     * 안 보일 수 있다 - 즉 첫 계산 때 signalCount가 실제로는 신호가 있는데도 0으로 나올 수 있는
     * 문제였다(H2를 REPEATABLE_READ로 강제해 직접 재현 확인함). checkAndSave()가 이미 판정한 결과를
     * 그대로 세면 이 문제를 원천적으로 피한다.
     */
    @Transactional
    public RiskAnalysisSummaryResponse checkAndSummarize(Long userId, Long propertyId) {
        int signalCount = checkAndSave(userId, propertyId);
        return new RiskAnalysisSummaryResponse(propertyId, signalCount, policyConfig.getVersion(), LocalDateTime.now());
    }

    /**
     * 신호 4종을 각각 독립적으로 판정·저장한다. 시세비교(comparison)를 한 번만 조회해 각 탐지기에
     * 넘기되, 그 결과를 어떻게 쓸지는(혹은 아예 무시할지는) 각 SignalDetector가 결정한다 —
     * 시세비교가 실패/판정불가여도 이를 필요로 하지 않는 신호(중복매물/동일계정/재등록)는
     * 자체적으로 판정을 계속 시도한다. 반환값은 실제로 리스크가 발견된(SUCCESS + 설명 존재) 신호
     * 개수 - checkAndSummarize()의 signalCount로 그대로 쓰인다.
     */
    @Transactional
    public int checkAndSave(Property property) {
        MarketComparison comparison = marketDataClient.getComparison(property.getId())
                .orElse(null);

        int foundSignalCount = (int) detectors.stream()
                .filter(SignalDetector::isEnabled)
                .filter(detector -> checkAndSaveSignal(property, comparison, detector))
                .count();

        // 신호 4종 중 신규 insert된 결과는 이미 REQUIRES_NEW로 별도 커밋됐으므로, 이 호출이 예외를
        // 던져 바깥 트랜잭션이 롤백돼도 그 결과는 그대로 남는다 - 여기서 예외를 그대로 전파하면
        // "기존 행이라 dirty-check만 해둔" 결과만 롤백되고 신규 insert 결과는 남아, 신호별로 반영
        // 여부가 갈리는 불일치 상태가 생긴다. RiskRecalculationService와 같은 원칙(재계산 실패가
        // 다른 처리에 영향을 주면 안 됨)으로 예외를 흡수하고 로그만 남긴다.
        try {
            depositSafetyCheckService.checkAndSave(property);
        } catch (RuntimeException e) {
            log.error("보증금 안전성 체크 실패 - propertyId={}", property.getId(), e);
        }
        return foundSignalCount;
    }

    private boolean checkAndSaveSignal(Property property, MarketComparison comparison, SignalDetector detector) {
        RiskSignalType signalType = detector.type();
        SignalCheckResult result = detector.detect(property, comparison);

        upsertCheck(property, signalType, result.status(), result.reason());
        upsertRisk(property, signalType, result);

        return result.status() == RiskCheckStatus.SUCCESS && result.description() != null;
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
            updateCheckAbsorbingConflict(property.getId(), signalType, status, reason);
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
                                // 복구 재조회 자체도 세 번째 요청과 @Version 충돌할 수 있다 - 그마저도
                                // 이미 유효한 값으로 반영된 상태이므로 흡수한다(saveCheckAbsorbingConflict와
                                // 동일 이유).
                                try {
                                    riskCheckRepository.saveAndFlush(winner);
                                } catch (ObjectOptimisticLockingFailureException ex) {
                                    log.warn("PropertyRiskCheck 복구 중 동시 갱신 경쟁 감지 - 나중에 커밋된 값만 반영됨. propertyId={}, signalType={}",
                                            property.getId(), signalType);
                                }
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
            updateRiskAbsorbingConflict(property.getId(), signalType, result.description());
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
                                // 복구 재조회 자체도 세 번째 요청과 @Version 충돌할 수 있다 - 그마저도
                                // 이미 유효한 값으로 반영된 상태이므로 흡수한다(saveRiskAbsorbingConflict와
                                // 동일 이유).
                                try {
                                    riskRepository.saveAndFlush(winner);
                                } catch (ObjectOptimisticLockingFailureException ex) {
                                    log.warn("PropertyRisk 복구 중 동시 갱신 경쟁 감지 - 나중에 커밋된 값만 반영됨. propertyId={}, signalType={}",
                                            property.getId(), signalType);
                                }
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

    // @Version 낙관적 락 충돌(동시 갱신 경쟁) 흡수 - 조회·수정·flush를 전부 REQUIRES_NEW 트랜잭션
    // 안에서 끝낸다. 바깥 트랜잭션에서 읽은 엔티티를 여기서 mutate만 하고 별도 트랜잭션에서 flush하면,
    // 바깥 세션은 그 엔티티가 이미 다른 세션에서 저장된 걸 몰라 여전히 dirty 상태로 남아있다가 다음
    // signalType 조회 시 Hibernate의 auto-flush가 낡은 버전으로 다시 flush를 시도해 충돌한다(실제
    // FakeListingSignalServiceConcurrencyTest로 재현·확인함) - 그래서 재조회부터 다시 REQUIRES_NEW
    // 안에서 하는, insert 경쟁 복구와 동일한 패턴을 쓴다. 두 판정 다 유효한 결과라 데이터 손상이
    // 아니므로(risk-analysis-design.md 전수조사 결과 코드 품질 2번), RiskRecalculationService의
    // fail-safe 원칙과 동일하게 로그만 남기고 조용히 흡수한다.
    private void updateCheckAbsorbingConflict(Long propertyId, RiskSignalType signalType, RiskCheckStatus status, RiskCheckReason reason) {
        try {
            requiresNewTransactionTemplate.executeWithoutResult(txStatus ->
                    riskCheckRepository.findByPropertyIdAndSignalType(propertyId, signalType)
                            .ifPresent(found -> {
                                found.overwrite(status, reason, policyConfig.getVersion());
                                riskCheckRepository.saveAndFlush(found);
                            }));
        } catch (ObjectOptimisticLockingFailureException e) {
            log.warn("PropertyRiskCheck 동시 갱신 경쟁 감지 - 나중에 커밋된 값만 반영됨. propertyId={}, signalType={}",
                    propertyId, signalType);
        }
    }

    private void updateRiskAbsorbingConflict(Long propertyId, RiskSignalType signalType, String description) {
        try {
            requiresNewTransactionTemplate.executeWithoutResult(txStatus ->
                    riskRepository.findByPropertyIdAndSignalType(propertyId, signalType)
                            .ifPresent(found -> {
                                found.overwrite(description);
                                riskRepository.saveAndFlush(found);
                            }));
        } catch (ObjectOptimisticLockingFailureException e) {
            log.warn("PropertyRisk 동시 갱신 경쟁 감지 - 나중에 커밋된 값만 반영됨. propertyId={}, signalType={}",
                    propertyId, signalType);
        }
    }
}
