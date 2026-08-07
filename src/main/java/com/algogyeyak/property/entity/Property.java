package com.algogyeyak.property.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * 매물(Property) 애그리거트 루트.
 * 서비스 타겟(사회초년생/대학생)에 맞춰 전월세(JEONSE/MONTHLY_RENT)만 지원한다.
 * userId는 User 도메인이 아직 없어 임시로 순수 FK 컬럼으로만 둔다
 * (User 엔티티가 생기면 연관관계로 바꿀지 팀과 논의 필요).
 */
@Entity
@Table(name = "property")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
public class Property {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PropertyType propertyType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TransactionType transactionType;

    @Column(nullable = false)
    private Long deposit;

    private Long monthlyRent;

    @Column(nullable = false)
    private Double area;

    // 관리비 없는 매물도 있어 nullable. 선택 입력값이라 등록/수정 시 항상 넘어오지 않을 수 있음.
    @Column(name = "maintenance_fee")
    private Long maintenanceFee;

    @Column(length = 1000)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PropertyStatus status;

    @OneToOne(mappedBy = "property", cascade = CascadeType.ALL, orphanRemoval = true)
    private PropertyAddress address;

    @OneToMany(mappedBy = "property", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<PropertyImage> images = new ArrayList<>();

    @CreatedDate
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    private LocalDateTime updatedAt;

    @Builder
    private Property(
            Long userId,
            String title,
            PropertyType propertyType,
            TransactionType transactionType,
            Long deposit,
            Long monthlyRent,
            Double area,
            Long maintenanceFee,
            String description
    ) {
        this.userId = userId;
        this.title = title;
        this.propertyType = propertyType;
        this.transactionType = transactionType;
        this.deposit = deposit;
        this.monthlyRent = monthlyRent;
        this.area = area;
        this.maintenanceFee = maintenanceFee;
        this.description = description;
        this.status = PropertyStatus.ACTIVE;
    }

    public void assignAddress(PropertyAddress address) {
        this.address = address;
        address.assignProperty(this);
    }

    public void addImage(PropertyImage image) {
        this.images.add(image);
        image.assignProperty(this);
    }

    // 수정 시 이미지 목록을 통째로 교체하기 위한 클리어. orphanRemoval=true라 컬렉션에서 빼면
    // 다음 flush 때 DB에서도 실제로 삭제된다 - 별도로 imageRepository.delete()를 호출할 필요 없다.
    public void clearImages() {
        this.images.clear();
    }

    public void updateTitle(String title) {
        this.title = title;
    }

    public void updatePriceInfo(Long deposit, Long monthlyRent) {
        this.deposit = deposit;
        this.monthlyRent = monthlyRent;
    }

    public void updateArea(Double area) {
        this.area = area;
    }

    public void updateMaintenanceFee(Long maintenanceFee) {
        this.maintenanceFee = maintenanceFee;
    }

    public void updateDescription(String description) {
        this.description = description;
    }

    public void delete() {
        this.status = PropertyStatus.DELETED;
    }

    public boolean isOwnedBy(Long userId) {
        return this.userId.equals(userId);
    }

    public boolean isDeleted() {
        return this.status == PropertyStatus.DELETED;
    }
}
