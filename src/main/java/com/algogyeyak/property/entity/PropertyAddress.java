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

    // 동/호수 등 상세주소. Kakao 지오코딩 결과가 아니라 사용자가 직접 입력하는 자유 텍스트라 다른
    // 필드들과 달리 null 허용 + 등록 이후에도 수정 가능하다(5차 멘토링 피드백 3번 - 같은 건물이라도
    // 호수가 다르면 다른 매물인데 구분할 방법이 없어 title에 호수를 끼워넣는 편법을 쓰고 있었음).
    // 지오코딩/국토부 실거래가 매칭엔 쓰이지 않는 순수 표시·식별용 필드라, "등록 시 확정된 주소는
    // 수정 불가"라는 roadAddress/jibunAddress/latitude/longitude의 제약과는 무관하게 둔다.
    @Column(length = 100)
    private String detailAddress;

    @Builder
    public PropertyAddress(String roadAddress, String jibunAddress, Double latitude, Double longitude, String detailAddress) {
        this.roadAddress = roadAddress;
        this.jibunAddress = jibunAddress;
        this.latitude = latitude;
        this.longitude = longitude;
        this.detailAddress = detailAddress;
    }

    void assignProperty(Property property) {
        this.property = property;
    }

    public void updateDetailAddress(String detailAddress) {
        this.detailAddress = detailAddress;
    }
}
