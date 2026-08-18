package com.algogyeyak.riskanalysis.entity;

import com.algogyeyak.property.entity.Property;
import com.algogyeyak.riskanalysis.enums.RiskSignalType;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "property_risks", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"property_id", "signal_type"})
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PropertyRisk {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "property_id", nullable = false)
    private Property property;

    @Enumerated(EnumType.STRING)
    @Column(name = "signal_type", nullable = false)
    private RiskSignalType signalType;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String description;

    @Version
    private Long version;

    @Builder
    private PropertyRisk(Property property, RiskSignalType signalType, String description) {
        this.property = property;
        this.signalType = signalType;
        this.description = description;
    }

    public static PropertyRisk of(Property property, RiskSignalType signalType, String description) {
        return PropertyRisk.builder()
                .property(property)
                .signalType(signalType)
                .description(description)
                .build();
    }

    /** 덮어쓰기 갱신 (재계산 시 사용) */
    public void overwrite(String description) {
        this.description = description;
    }
}
