package com.algogyeyak.property.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Kakao Local API로 정규화된 매물 주소.
 * 단독/다가구는 지번 일부가 비공개라 roadAddress가 null일 수 있음 (jibunAddress는 항상 존재).
 */
@Entity
@Table(name = "property_address")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PropertyAddress {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "property_id", nullable = false, unique = true)
    private Property property;

    @Column(length = 255)
    private String roadAddress;

    @Column(nullable = false, length = 255)
    private String jibunAddress;

    @Column(nullable = false)
    private Double latitude;

    @Column(nullable = false)
    private Double longitude;

    @Builder
    public PropertyAddress(String roadAddress, String jibunAddress, Double latitude, Double longitude) {
        this.roadAddress = roadAddress;
        this.jibunAddress = jibunAddress;
        this.latitude = latitude;
        this.longitude = longitude;
    }

    void assignProperty(Property property) {
        this.property = property;
    }
}
