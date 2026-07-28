package com.algogyeyak.riskanalysis.entity;

import com.algogyeyak.riskanalysis.enums.RiskSignalType;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "property_risks")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PropertyRisk {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "risk_check_id", nullable = false)
    private Long riskCheckId;

    @Column(name = "property_id", nullable = false)
    private Long propertyId;

    @Enumerated(EnumType.STRING)
    @Column(name = "signal_type", nullable = false)
    private RiskSignalType signalType;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String description;

    @Column(name = "detected_at", nullable = false)
    private LocalDateTime detectedAt;

    @Builder
    private PropertyRisk(Long riskCheckId, Long propertyId, RiskSignalType signalType,
                         String description, LocalDateTime detectedAt) {
        this.riskCheckId = riskCheckId;
        this.propertyId = propertyId;
        this.signalType = signalType;
        this.description = description;
        this.detectedAt = detectedAt;
    }

    public static PropertyRisk of(Long riskCheckId, Long propertyId,
                                  RiskSignalType type, String description) {
        return PropertyRisk.builder()
                .riskCheckId(riskCheckId)
                .propertyId(propertyId)
                .signalType(type)
                .description(description)
                .detectedAt(LocalDateTime.now())
                .build();
    }
}
