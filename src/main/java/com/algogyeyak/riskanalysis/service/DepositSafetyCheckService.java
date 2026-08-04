package com.algogyeyak.riskanalysis.service;

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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Optional;

/**
 * 보증금과 매매시세를 이용해 전세가율(보증금/매매시세)을 계산·저장한다. 선순위보증금/근저당
 * 채권최고액을 반영한 정밀 재계산은 별도 트리거(POST /deposit-safety/recalculate, 미구현)의
 * 몫이라 이 기본 계산에는 항상 null로 저장한다.
 */
@Service
@RequiredArgsConstructor
public class DepositSafetyCheckService {

    private final DepositSafetyCheckRepository depositSafetyCheckRepository;
    private final MarketSaleDataClient marketSaleDataClient;
    private final PropertyRepository propertyRepository;
    private final RiskPolicyConfig policyConfig;

    /**
     * 매물의 보증금 안전성 체크 결과를 조회한다. checklist/risk-signals와 동일한 패턴으로 매물
     * 존재/삭제/소유권을 확인한 뒤, 아직 한 번도 계산된 적 없으면(check == null) status가 null인
     * 응답을 그대로 반환한다 - 이 메서드는 조회 전용이라 계산을 트리거하지 않는다.
     */
    public DepositSafetyCheckResponse get(Long userId, Long propertyId) {
        Property property = propertyRepository.findById(propertyId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PROPERTY_NOT_FOUND));
        if (property.isDeleted()) {
            throw new BusinessException(ErrorCode.PROPERTY_NOT_FOUND);
        }
        if (!property.isOwnedBy(userId)) {
            throw new BusinessException(ErrorCode.PROPERTY_ACCESS_DENIED);
        }

        DepositSafetyCheck check = depositSafetyCheckRepository.findByPropertyId(propertyId).orElse(null);
        return DepositSafetyCheckResponse.from(propertyId, check);
    }

    @Transactional
    public void checkAndSave(Property property) {
        if (property.getTransactionType() == TransactionType.MONTHLY_RENT) {
            upsertUnavailable(property, DepositSafetyCheckReason.TRANSACTION_TYPE_UNSUPPORTED);
            return;
        }

        if (property.getDeposit() == null || property.getDeposit() <= 0) {
            upsertUnavailable(property, DepositSafetyCheckReason.DEPOSIT_INFO_MISSING);
            return;
        }

        Optional<MarketSalePrice> salePrice = marketSaleDataClient.getSalePrice(property.getId());
        if (salePrice.isEmpty()) {
            upsertUnavailable(property, DepositSafetyCheckReason.ESTIMATED_PRICE_MISSING);
            return;
        }

        BigDecimal ratio = calculateRatioPercent(BigDecimal.valueOf(property.getDeposit()), salePrice.get().referencePrice());
        upsertCalculated(property, ratio, salePrice.get().referenceDate(), buildExplanation(ratio));
    }

    private BigDecimal calculateRatioPercent(BigDecimal numerator, BigDecimal salePrice) {
        return numerator.divide(salePrice, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(0, RoundingMode.HALF_UP);
    }

    // 안전(80% 미만)/주의(80~100%)/위험(100~150%)/입력값 재확인(150% 초과) 4단계 - 임계값은
    // RiskPolicyConfig의 jeonseRatioCautionFrom/WarnFrom/WarnTo/AlertOver로 관리한다.
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

    private void upsertUnavailable(Property property, DepositSafetyCheckReason reason) {
        depositSafetyCheckRepository.findByPropertyId(property.getId()).ifPresentOrElse(
                existing -> existing.overwrite(null, null, null, null, null, reason, policyConfig.getVersion(), DepositSafetyStatus.UNAVAILABLE),
                () -> depositSafetyCheckRepository.save(
                        DepositSafetyCheck.unavailable(property, null, null, reason, policyConfig.getVersion()))
        );
    }

    private void upsertCalculated(Property property, BigDecimal ratio, LocalDate referenceDate, String explanation) {
        depositSafetyCheckRepository.findByPropertyId(property.getId()).ifPresentOrElse(
                existing -> existing.overwrite(ratio, null, null, referenceDate, explanation, null, policyConfig.getVersion(), DepositSafetyStatus.CALCULATED),
                () -> depositSafetyCheckRepository.save(
                        DepositSafetyCheck.calculated(property, ratio, null, null, referenceDate, explanation, policyConfig.getVersion()))
        );
    }
}
