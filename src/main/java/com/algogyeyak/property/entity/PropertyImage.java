package com.algogyeyak.property.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "property_image")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PropertyImage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "property_id", nullable = false)
    private Property property;

    @Column(nullable = false, length = 500)
    private String imageUrl;

    // 어느 공간(거실/침실 등) 사진인지 라벨. 필수 아님 - 라벨 없이 올릴 수도 있다.
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private RoomType roomType;

    private Integer sortOrder;

    @Builder
    public PropertyImage(String imageUrl, RoomType roomType, Integer sortOrder) {
        this.imageUrl = imageUrl;
        this.roomType = roomType;
        this.sortOrder = sortOrder;
    }

    void assignProperty(Property property) {
        this.property = property;
    }
}
