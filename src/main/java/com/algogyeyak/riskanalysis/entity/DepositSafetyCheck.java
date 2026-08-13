package com.algogyeyak.riskanalysis.entity;

import com.algogyeyak.property.entity.Property;
import com.algogyeyak.riskanalysis.enums.DepositSafetyCheckReason;
import com.algogyeyak.riskanalysis.enums.DepositSafetyStatus;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "property_deposit_safety_checks")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DepositSafetyCheck {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "property_id", nullable = false, unique = true)
    private Property property;

    @Column(name = "jeonse_ratio", precision = 6, scale = 2)
    private BigDecimal jeonseRatio; // % 단위, status=CALCULATED일 때만 값 존재

    @Column(name = "senior_deposit", precision = 15, scale = 0)
    private BigDecimal seniorDeposit; // 선택 입력, KRW

    @Column(name = "max_claim_amount", precision = 15, scale = 0)
    private BigDecimal maxClaimAmount; // 근저당 채권최고액, 선택 입력, KRW

    @Column(name = "reference_date")
    private LocalDate referenceDate; // 계산에 사용한 매매 실거래가 표본 중 최신 계약일. status=CALCULATED일 때만 존재

    @Column(columnDefinition = "TEXT")
    private String explanation;

    @Column(name = "sample_count")
    private Integer sampleCount; // 기준가 산출에 쓰인 매매 실거래가 표본 수. status=CALCULATED일 때만 존재

    @Column(name = "radius_meters")
    private Integer radiusMeters; // 표본 탐색에 실제 사용된 반경(300 또는 600). status=CALCULATED일 때만 존재

    @Enumerated(EnumType.STRING)
    @Column(length = 255)
    private DepositSafetyCheckReason reason; // status=CALCULATED면 null

    @Column(name = "policy_version", nullable = false)
    private String policyVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DepositSafetyStatus status;

    @Column(name = "calculated_at", nullable = false)
    private LocalDateTime calculatedAt;

    @Builder
    private DepositSafetyCheck(Property property, BigDecimal jeonseRatio, BigDecimal seniorDeposit,
                               BigDecimal maxClaimAmount, LocalDate referenceDate, String explanation,
                               Integer sampleCount, Integer radiusMeters,
                               DepositSafetyCheckReason reason, String policyVersion, DepositSafetyStatus status,
                               LocalDateTime calculatedAt) {
        this.property = property;
        this.jeonseRatio = jeonseRatio;
        this.seniorDeposit = seniorDeposit;
        this.maxClaimAmount = maxClaimAmount;
        this.referenceDate = referenceDate;
        this.explanation = explanation;
        this.sampleCount = sampleCount;
        this.radiusMeters = radiusMeters;
        this.reason = reason;
        this.policyVersion = policyVersion;
        this.status = status;
        this.calculatedAt = calculatedAt;
    }

    public static DepositSafetyCheck calculated(Property property, BigDecimal jeonseRatio, BigDecimal seniorDeposit,
                                                 BigDecimal maxClaimAmount, LocalDate referenceDate,
                                                 String explanation, Integer sampleCount, Integer radiusMeters,
                                                 String policyVersion) {
        return DepositSafetyCheck.builder()
                .property(property)
                .jeonseRatio(jeonseRatio)
                .seniorDeposit(seniorDeposit)
                .maxClaimAmount(maxClaimAmount)
                .referenceDate(referenceDate)
                .explanation(explanation)
                .sampleCount(sampleCount)
                .radiusMeters(radiusMeters)
                .reason(null)
                .policyVersion(policyVersion)
                .status(DepositSafetyStatus.CALCULATED)
                .calculatedAt(LocalDateTime.now())
                .build();
    }

    public static DepositSafetyCheck unavailable(Property property, BigDecimal seniorDeposit, BigDecimal maxClaimAmount,
                                                  DepositSafetyCheckReason reason, String policyVersion) {
        return DepositSafetyCheck.builder()
                .property(property)
                .seniorDeposit(seniorDeposit)
                .maxClaimAmount(maxClaimAmount)
                .reason(reason)
                .policyVersion(policyVersion)
                .status(DepositSafetyStatus.UNAVAILABLE)
                .calculatedAt(LocalDateTime.now())
                .build();
    }

    public static DepositSafetyCheck failed(Property property, BigDecimal seniorDeposit, BigDecimal maxClaimAmount,
                                             DepositSafetyCheckReason reason, String policyVersion) {
        return DepositSafetyCheck.builder()
                .property(property)
                .seniorDeposit(seniorDeposit)
                .maxClaimAmount(maxClaimAmount)
                .reason(reason)
                .policyVersion(policyVersion)
                .status(DepositSafetyStatus.FAILED)
                .calculatedAt(LocalDateTime.now())
                .build();
    }

    /** 덮어쓰기 갱신 (재계산 시 사용) */
    public void overwrite(BigDecimal jeonseRatio, BigDecimal seniorDeposit, BigDecimal maxClaimAmount,
                          LocalDate referenceDate, String explanation, Integer sampleCount, Integer radiusMeters,
                          DepositSafetyCheckReason reason, String policyVersion, DepositSafetyStatus status) {
        this.jeonseRatio = jeonseRatio;
        this.seniorDeposit = seniorDeposit;
        this.maxClaimAmount = maxClaimAmount;
        this.referenceDate = referenceDate;
        this.explanation = explanation;
        this.sampleCount = sampleCount;
        this.radiusMeters = radiusMeters;
        this.reason = reason;
        this.policyVersion = policyVersion;
        this.status = status;
        this.calculatedAt = LocalDateTime.now();
    }
}
