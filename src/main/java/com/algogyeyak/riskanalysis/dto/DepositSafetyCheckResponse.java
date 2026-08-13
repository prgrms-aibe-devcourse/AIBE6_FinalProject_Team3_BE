package com.algogyeyak.riskanalysis.dto;

import com.algogyeyak.riskanalysis.entity.DepositSafetyCheck;
import com.algogyeyak.riskanalysis.enums.DepositSafetyCheckReason;
import com.algogyeyak.riskanalysis.enums.DepositSafetyStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 매물의 보증금 안전성(전세가율) 조회(GET /deposit-safety) 응답. RiskSignalResponse와 같은
 * 패턴으로 status/reason/값 필드를 하나의 shape에 담는다 - status에 따라 어떤 필드가 채워지는지가
 * 달라질 뿐 별도의 "판정불가 응답 모양"을 두지 않는다.
 * POST /deposit-safety/recalculate로 계산된 결과도 이 응답 하나를 그대로 재사용한다.
 */
public record DepositSafetyCheckResponse(
        Long propertyId,
        DepositSafetyStatus status,
        Integer jeonseRatio,             // percent 정수, CALCULATED일 때만
        boolean seniorDepositApplied,    // 선순위보증금 반영 정밀 재계산 적용 여부
        Long seniorDeposit,               // 반영된 경우에만 값 존재
        Long maxClaimAmount,              // 반영된 경우에만 값 존재
        String explanation,              // CALCULATED일 때만
        LocalDate referenceDate,         // CALCULATED일 때만
        Integer sampleCount,             // 기준가 산출에 쓰인 표본 수. CALCULATED일 때만
        Integer radiusMeters,            // 표본 탐색 반경(300 또는 600). CALCULATED일 때만
        DepositSafetyCheckReason reason, // UNAVAILABLE/FAILED일 때만
        LocalDateTime calculatedAt,
        String disclaimer,
        boolean recentOwnershipChangeWarning, // 최근 소유권 변경 + 높은 전세가율(jeonseRatioWarnFrom 이상) 조합일 때만 true
        Integer cautionFrom,             // 전세가율 판정 기준값(%) - 이 값부터 "주의"
        Integer warnFrom,                // 이 값부터 "위험"
        Integer warnTo                   // 이 값을 넘으면 "입력값 재확인 안내"
) {
    public static final String DISCLAIMER = "확정 판단이 아닌 참고용 정보이며, 법률·등기 검토를 대체하지 않습니다.";

    // check가 null이면 아직 한 번도 checkAndSave()가 실행된 적 없다는 뜻 - status도 null로 둔다.
    // 판정 기준값(cautionFrom/warnFrom/warnTo)은 계산 여부와 무관하게 항상 내려준다 - "근거가 되는 기준"을
    // 노출해달라는 피드백에 맞춰, 계산 전에도 사용자가 기준 자체는 확인할 수 있게 한다.
    public static DepositSafetyCheckResponse from(Long propertyId, DepositSafetyCheck check, boolean recentOwnershipChangeWarning,
                                                   Integer cautionFrom, Integer warnFrom, Integer warnTo) {
        if (check == null) {
            return new DepositSafetyCheckResponse(
                    propertyId, null, null, false, null, null, null, null, null, null, null, null, DISCLAIMER, false,
                    cautionFrom, warnFrom, warnTo);
        }

        boolean calculated = check.getStatus() == DepositSafetyStatus.CALCULATED;
        return new DepositSafetyCheckResponse(
                propertyId,
                check.getStatus(),
                calculated ? check.getJeonseRatio().intValue() : null,
                check.getSeniorDeposit() != null,
                check.getSeniorDeposit() != null ? check.getSeniorDeposit().longValue() : null,
                check.getMaxClaimAmount() != null ? check.getMaxClaimAmount().longValue() : null,
                calculated ? check.getExplanation() : null,
                calculated ? check.getReferenceDate() : null,
                calculated ? check.getSampleCount() : null,
                calculated ? check.getRadiusMeters() : null,
                calculated ? null : check.getReason(),
                check.getCalculatedAt(),
                DISCLAIMER,
                calculated && recentOwnershipChangeWarning,
                cautionFrom,
                warnFrom,
                warnTo
        );
    }
}
