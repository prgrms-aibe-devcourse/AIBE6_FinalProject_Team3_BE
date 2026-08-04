package com.algogyeyak.riskanalysis.entity;

import com.algogyeyak.property.entity.Property;
import com.algogyeyak.riskanalysis.enums.RiskCheckReason;
import com.algogyeyak.riskanalysis.enums.RiskCheckStatus;
import com.algogyeyak.riskanalysis.enums.RiskSignalType;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "property_risk_checks", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"property_id", "signal_type"})
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PropertyRiskCheck {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "property_id", nullable = false)
    private Property property;

    @Enumerated(EnumType.STRING)
    @Column(name = "signal_type", nullable = false)
    private RiskSignalType signalType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RiskCheckStatus status;

    @Enumerated(EnumType.STRING)
    private RiskCheckReason reason; // status=SUCCESS면 null

    @Column(name = "policy_version", nullable = false)
    private String policyVersion;

    @Column(name = "checked_at", nullable = false)
    private LocalDateTime checkedAt;

    @Builder
    private PropertyRiskCheck(Property property, RiskSignalType signalType, RiskCheckStatus status,
                              RiskCheckReason reason, String policyVersion,
                              LocalDateTime checkedAt) {
        this.property = property;
        this.signalType = signalType;
        this.status = status;
        this.reason = reason;
        this.policyVersion = policyVersion;
        this.checkedAt = checkedAt;
    }

    public static PropertyRiskCheck success(Property property, RiskSignalType signalType, String policyVersion) {
        return PropertyRiskCheck.builder()
                .property(property)
                .signalType(signalType)
                .status(RiskCheckStatus.SUCCESS)
                .reason(null)
                .policyVersion(policyVersion)
                .checkedAt(LocalDateTime.now())
                .build();
    }

    public static PropertyRiskCheck undeterminable(Property property, RiskSignalType signalType, RiskCheckReason reason, String policyVersion) {
        return PropertyRiskCheck.builder()
                .property(property)
                .signalType(signalType)
                .status(RiskCheckStatus.UNDETERMINABLE)
                .reason(reason)
                .policyVersion(policyVersion)
                .checkedAt(LocalDateTime.now())
                .build();
    }

    public static PropertyRiskCheck failed(Property property, RiskSignalType signalType, RiskCheckReason reason, String policyVersion) {
        return PropertyRiskCheck.builder()
                .property(property)
                .signalType(signalType)
                .status(RiskCheckStatus.FAILED)
                .reason(reason)
                .policyVersion(policyVersion)
                .checkedAt(LocalDateTime.now())
                .build();
    }

    /** 덮어쓰기 갱신 (재계산 시 사용) */
    public void overwrite(RiskCheckStatus status, RiskCheckReason reason, String policyVersion) {
        this.status = status;
        this.reason = reason;
        this.policyVersion = policyVersion;
        this.checkedAt = LocalDateTime.now();
    }
}
