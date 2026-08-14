package com.algogyeyak.riskanalysis.service;

import com.algogyeyak.checklist.entity.ChecklistItem;
import com.algogyeyak.checklist.entity.ChecklistItemCode;
import com.algogyeyak.checklist.repository.ChecklistItemRepository;
import com.algogyeyak.global.error.ErrorCode;
import com.algogyeyak.global.exception.BusinessException;
import com.algogyeyak.property.entity.Property;
import com.algogyeyak.property.entity.TransactionType;
import com.algogyeyak.property.repository.PropertyRepository;
import com.algogyeyak.riskanalysis.client.MarketSaleDataClient;
import com.algogyeyak.riskanalysis.dto.DepositSafetyCheckResponse;
import com.algogyeyak.riskanalysis.dto.MarketSalePrice;
import com.algogyeyak.riskanalysis.entity.DepositSafetyCheck;
import com.algogyeyak.riskanalysis.enums.DepositSafetyCheckReason;
import com.algogyeyak.riskanalysis.enums.DepositSafetyStatus;
import com.algogyeyak.riskanalysis.policy.RiskPolicyConfig;
import com.algogyeyak.riskanalysis.repository.DepositSafetyCheckRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Optional;

/**
 * 보증금과 매매시세를 이용해 전세가율(보증금/매매시세)을 계산·저장한다. checkAndSave(Property)는
 * POST /risk-analysis 흐름에서 선순위보증금 없이 기본 계산만 하고, recalculate()는
 * POST /deposit-safety/recalculate에서 사용자가 입력한 선순위보증금/근저당 채권최고액을 반영해
 * 정밀 재계산한다 - 둘 다 같은 calculate()를 공유하고 seniorDeposit/maxClaimAmount만 다르다.
 */
@Service
@Slf4j
public class DepositSafetyCheckService {

    private final DepositSafetyCheckRepository depositSafetyCheckRepository;
    private final MarketSaleDataClient marketSaleDataClient;
    private final PropertyRepository propertyRepository;
    private final ChecklistItemRepository checklistItemRepository;
    private final RiskPolicyConfig policyConfig;
    private final TransactionTemplate requiresNewTransactionTemplate;

    public DepositSafetyCheckService(
            DepositSafetyCheckRepository depositSafetyCheckRepository,
            MarketSaleDataClient marketSaleDataClient,
            PropertyRepository propertyRepository,
            ChecklistItemRepository checklistItemRepository,
            RiskPolicyConfig policyConfig,
            PlatformTransactionManager transactionManager) {
        this.depositSafetyCheckRepository = depositSafetyCheckRepository;
        this.marketSaleDataClient = marketSaleDataClient;
        this.propertyRepository = propertyRepository;
        this.checklistItemRepository = checklistItemRepository;
        this.policyConfig = policyConfig;
        this.requiresNewTransactionTemplate = new TransactionTemplate(transactionManager);
        this.requiresNewTransactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * 매물의 보증금 안전성 체크 결과를 조회한다. checklist/risk-signals와 동일한 패턴으로 매물
     * 존재/삭제/소유권을 확인한 뒤, 아직 한 번도 계산된 적 없으면(check == null) status가 null인
     * 응답을 그대로 반환한다 - 이 메서드는 조회 전용이라 계산을 트리거하지 않는다.
     */
    public DepositSafetyCheckResponse get(Long userId, Long propertyId) {
        Property property = findOwnedProperty(userId, propertyId);
        DepositSafetyCheck check = depositSafetyCheckRepository.findByPropertyId(propertyId).orElse(null);
        return DepositSafetyCheckResponse.from(propertyId, check, isRecentOwnershipChangeWarning(propertyId, check),
                policyConfig.getJeonseRatioCautionFrom(), policyConfig.getJeonseRatioWarnFrom(), policyConfig.getJeonseRatioWarnTo());
    }

    @Transactional
    public void checkAndSave(Property property) {
        calculate(property, null, null);
    }

    /**
     * 사용자가 입력한 선순위보증금(+근저당 채권최고액)을 반영해 전세가율을 다시 계산·저장하고,
     * 갱신된 결과를 바로 반환한다(POST가 계산 결과를 직접 돌려주는 도메인 컨벤션과 동일).
     */
    @Transactional
    public DepositSafetyCheckResponse recalculate(Long userId, Long propertyId, Long seniorDeposit, Long maxClaimAmount) {
        Property property = findOwnedProperty(userId, propertyId);

        DepositSafetyCheck check = calculate(property,
                seniorDeposit != null ? BigDecimal.valueOf(seniorDeposit) : null,
                maxClaimAmount != null ? BigDecimal.valueOf(maxClaimAmount) : null);

        return DepositSafetyCheckResponse.from(propertyId, check, isRecentOwnershipChangeWarning(propertyId, check),
                policyConfig.getJeonseRatioCautionFrom(), policyConfig.getJeonseRatioWarnFrom(), policyConfig.getJeonseRatioWarnTo());
    }

    /**
     * "최근 소유권 변경 + 높은 전세가율" 보조 신호. 전세가율이 위험 구간(jeonseRatioWarnFrom 이상)이고,
     * checklist에 기록된 소유권 취득일이 최근(ownershipRecentChangeMonths 이내)이면 true.
     * 체크리스트 문항에 아직 응답이 없거나(value == null) 날짜 형식이 아니면 판단 근거가 없으니
     * 조용히 false로 처리한다 - 이 경고는 부가 정보라 판정 불가로 전체 응답을 막을 이유가 없다.
     */
    private boolean isRecentOwnershipChangeWarning(Long propertyId, DepositSafetyCheck check) {
        if (check == null || check.getStatus() != DepositSafetyStatus.CALCULATED) {
            return false;
        }
        if (check.getJeonseRatio().intValue() < policyConfig.getJeonseRatioWarnFrom()) {
            return false;
        }

        String rawAcquisitionDate = checklistItemRepository
                .findByChecklist_Property_IdAndCode(propertyId, ChecklistItemCode.OWNERSHIP_ACQUISITION_DATE)
                .map(ChecklistItem::getValue)
                .orElse(null);
        if (rawAcquisitionDate == null) {
            return false;
        }

        try {
            LocalDate acquisitionDate = LocalDate.parse(rawAcquisitionDate);
            return acquisitionDate.isAfter(LocalDate.now().minusMonths(policyConfig.getOwnershipRecentChangeMonths()));
        } catch (java.time.format.DateTimeParseException e) {
            return false;
        }
    }

    private Property findOwnedProperty(Long userId, Long propertyId) {
        Property property = propertyRepository.findById(propertyId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PROPERTY_NOT_FOUND));
        if (property.isDeleted()) {
            throw new BusinessException(ErrorCode.PROPERTY_NOT_FOUND);
        }
        if (!property.isOwnedBy(userId)) {
            throw new BusinessException(ErrorCode.PROPERTY_ACCESS_DENIED);
        }
        return property;
    }

    private DepositSafetyCheck calculate(Property property, BigDecimal seniorDeposit, BigDecimal maxClaimAmount) {
        if (property.getTransactionType() == TransactionType.MONTHLY_RENT) {
            return upsertUnavailable(property, seniorDeposit, maxClaimAmount, DepositSafetyCheckReason.TRANSACTION_TYPE_UNSUPPORTED);
        }

        if (property.getDeposit() == null || property.getDeposit() <= 0) {
            return upsertUnavailable(property, seniorDeposit, maxClaimAmount, DepositSafetyCheckReason.DEPOSIT_INFO_MISSING);
        }

        Optional<MarketSalePrice> salePrice = marketSaleDataClient.getSalePrice(property.getId());
        if (salePrice.isEmpty()) {
            return upsertUnavailable(property, seniorDeposit, maxClaimAmount, DepositSafetyCheckReason.ESTIMATED_PRICE_MISSING);
        }

        BigDecimal numerator = BigDecimal.valueOf(property.getDeposit());
        if (seniorDeposit != null) {
            numerator = numerator.add(seniorDeposit);
        }
        if (maxClaimAmount != null) {
            numerator = numerator.add(maxClaimAmount);
        }

        BigDecimal ratio = calculateRatioPercent(numerator, salePrice.get().referencePrice());
        return upsertCalculated(property, ratio, seniorDeposit, maxClaimAmount, salePrice.get().referenceDate(), buildExplanation(ratio),
                salePrice.get().sampleCount(), salePrice.get().radiusMeters());
    }

    private BigDecimal calculateRatioPercent(BigDecimal numerator, BigDecimal salePrice) {
        return numerator.divide(salePrice, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(0, RoundingMode.HALF_UP);
    }

    // 안전(80% 미만)/주의(80~100%)/위험(100~150%)/입력값 재확인(150% 초과) 4단계 - 임계값은
    // RiskPolicyConfig의 jeonseRatioCautionFrom/WarnFrom/WarnTo로 관리한다.
    private String buildExplanation(BigDecimal ratioPercent) {
        int ratio = ratioPercent.intValue();
        if (ratio < policyConfig.getJeonseRatioCautionFrom()) {
            return "이 집 전세가율은 %d%%예요. 안전한 편이에요.".formatted(ratio);
        }
        if (ratio < policyConfig.getJeonseRatioWarnFrom()) {
            return ("이 집 전세가율은 %d%%예요. 보통 %d%%가 넘으면 집값이 떨어질 때 보증금을 못 돌려받을 "
                    + "위험이 커진다고 봐요. 주의가 필요해요.").formatted(ratio, policyConfig.getJeonseRatioCautionFrom());
        }
        if (ratio <= policyConfig.getJeonseRatioWarnTo()) {
            return "이 집 전세가율은 %d%%예요. 위험 수준이에요 — 집값이 떨어지면 보증금을 못 돌려받을 가능성이 높아요."
                    .formatted(ratio);
        }
        return "이 집 전세가율은 %d%%예요. 매우 높은 수치라 입력값을 다시 확인해보시는 게 좋아요.".formatted(ratio);
    }

    // "없으면 insert, 있으면 update"인데 조회와 insert 사이에 갭이 있어, 같은 매물에 대한 두 요청이
    // 동시에 들어오면(POST /risk-analysis와 POST /deposit-safety/recalculate가 겹치는 경우 등) 둘 다
    // "기존 행 없음"을 보고 동시에 insert를 시도해 property_id 유니크 제약을 위반할 수 있다
    // (DataIntegrityViolationException). insert를 REQUIRES_NEW로 격리해서, 위반이 나도 그 임시
    // 트랜잭션의 세션만 버려지고 이 메서드가 속한 바깥 트랜잭션의 세션은 정상 상태로 남게 한다 -
    // 같은 세션에서 saveAndFlush가 유니크 제약 위반으로 실패한 뒤 그 세션으로 쿼리를 이어가면
    // Hibernate가 "세션이 예외 이후 flush됨(AssertionFailure)"을 던지는 문제를 피하기 위함
    // (CustomOAuth2UserService.createUser()와 동일한 이유·동일한 패턴). 실패하면 그 사이 먼저 커밋된
    // 행을 재조회해서 덮어쓴다.
    private DepositSafetyCheck upsertUnavailable(Property property, BigDecimal seniorDeposit, BigDecimal maxClaimAmount, DepositSafetyCheckReason reason) {
        Optional<DepositSafetyCheck> existing = depositSafetyCheckRepository.findByPropertyId(property.getId());
        if (existing.isPresent()) {
            existing.get().overwrite(null, seniorDeposit, maxClaimAmount, null, null, null, null, reason, policyConfig.getVersion(), DepositSafetyStatus.UNAVAILABLE);
            return existing.get();
        }

        DepositSafetyCheck newCheck = DepositSafetyCheck.unavailable(property, seniorDeposit, maxClaimAmount, reason, policyConfig.getVersion());
        try {
            requiresNewTransactionTemplate.executeWithoutResult(status -> depositSafetyCheckRepository.saveAndFlush(newCheck));
            return newCheck;
        } catch (DataIntegrityViolationException e) {
            // 재조회도 REQUIRES_NEW로 새 트랜잭션에서 한다 - 바깥 트랜잭션에서 그대로 재조회하면,
            // MySQL InnoDB의 기본 격리수준(REPEATABLE READ)에서는 이 트랜잭션이 이미 앞서 읽은 시점의
            // 스냅샷에 갇혀 있어 방금 다른 트랜잭션이 커밋한 승자 행이 안 보일 수 있다(H2는 기본이
            // READ_COMMITTED라 로컬 테스트에서는 드러나지 않는다). 새 트랜잭션은 항상 새 스냅샷을
            // 잡으므로 격리수준과 무관하게 승자를 확실히 볼 수 있다.
            DepositSafetyCheck winner = requiresNewTransactionTemplate.execute(status ->
                    depositSafetyCheckRepository.findByPropertyId(property.getId())
                            .map(found -> {
                                found.overwrite(null, seniorDeposit, maxClaimAmount, null, null, null, null, reason, policyConfig.getVersion(), DepositSafetyStatus.UNAVAILABLE);
                                depositSafetyCheckRepository.saveAndFlush(found);
                                return found;
                            })
                            .orElse(null));
            if (winner == null) {
                log.error("DepositSafetyCheck 동시 insert 경쟁 복구 실패 - propertyId={}", property.getId(), e);
                throw e;
            }
            return winner;
        }
    }

    private DepositSafetyCheck upsertCalculated(Property property, BigDecimal ratio, BigDecimal seniorDeposit, BigDecimal maxClaimAmount,
                                                 LocalDate referenceDate, String explanation, Integer sampleCount, Integer radiusMeters) {
        Optional<DepositSafetyCheck> existing = depositSafetyCheckRepository.findByPropertyId(property.getId());
        if (existing.isPresent()) {
            existing.get().overwrite(ratio, seniorDeposit, maxClaimAmount, referenceDate, explanation, sampleCount, radiusMeters, null, policyConfig.getVersion(), DepositSafetyStatus.CALCULATED);
            return existing.get();
        }

        DepositSafetyCheck newCheck = DepositSafetyCheck.calculated(property, ratio, seniorDeposit, maxClaimAmount, referenceDate, explanation, sampleCount, radiusMeters, policyConfig.getVersion());
        try {
            requiresNewTransactionTemplate.executeWithoutResult(status -> depositSafetyCheckRepository.saveAndFlush(newCheck));
            return newCheck;
        } catch (DataIntegrityViolationException e) {
            // upsertUnavailable()과 동일한 이유로 재조회도 REQUIRES_NEW 새 트랜잭션에서 한다.
            DepositSafetyCheck winner = requiresNewTransactionTemplate.execute(status ->
                    depositSafetyCheckRepository.findByPropertyId(property.getId())
                            .map(found -> {
                                found.overwrite(ratio, seniorDeposit, maxClaimAmount, referenceDate, explanation, sampleCount, radiusMeters, null, policyConfig.getVersion(), DepositSafetyStatus.CALCULATED);
                                depositSafetyCheckRepository.saveAndFlush(found);
                                return found;
                            })
                            .orElse(null));
            if (winner == null) {
                log.error("DepositSafetyCheck 동시 insert 경쟁 복구 실패 - propertyId={}", property.getId(), e);
                throw e;
            }
            return winner;
        }
    }
}
