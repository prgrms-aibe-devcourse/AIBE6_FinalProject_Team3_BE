package com.algogyeyak.property.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
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
@Table(name = "property", indexes = {
        // 관리자 통계 대시보드의 기간별 매물 등록 조회(PropertyRepository.countByCreatedAtBetween/
        // findCreatedAtBetween, AdminStatsService 참고)가 이 컬럼으로 조회한다 - User/PropertyReport는
        // 같은 이유로 이미 created_at 인덱스가 있는데 Property만 빠져 있어 이 조회만 풀스캔이었다
        // (2026-08-20 멘토링 피드백에서 지적).
        @Index(name = "idx_property_created_at", columnList = "created_at")
})
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

    // mappedBy(비소유) 쪽 @OneToOne은 fetch 타입과 무관하게 조인으로 한 번에 못 가져오고 매물마다
    // 별도 SELECT가 나가는 게 JPA의 잘 알려진 한계다 - 기본값(EAGER)이면 이 SELECT가 프록시 지연 없이
    // 즉시 나가 default_batch_fetch_size로 묶을 수 없으므로, LAZY로 명시해야 배치 효과가 적용된다.
    @OneToOne(mappedBy = "property", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
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
