package com.algogyeyak.property.repository;

import com.algogyeyak.property.entity.Property;
import com.algogyeyak.property.entity.PropertyStatus;
import com.algogyeyak.property.entity.PropertyType;
import com.algogyeyak.property.entity.TransactionType;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PropertyRepository extends JpaRepository<Property, Long> {

    /**
     * 동일 사용자가 동일 주소·동일 거래유형으로 이미 등록해둔 매물이 있는지 확인 (PROPERTY_DUPLICATE 판단용).
     */
    boolean existsByUserIdAndTransactionTypeAndStatusAndAddress_RoadAddress(
            Long userId,
            TransactionType transactionType,
            PropertyStatus status,
            String roadAddress
    );

    /**
     * 도로명주소가 없는 경우(단독/다가구 등 지번만 있는 매물)를 위한 지번주소 기준 중복 체크.
     * 도로명주소 체크와 동일한 목적이지만, 정규화된 형태가 아닌 지번주소는 표기 흔들림에 더 취약할 수
     * 있다는 한계는 있다 - 그래도 "도로명주소 없으면 중복 체크 자체가 스킵됨"보다는 낫다고 판단.
     */
    boolean existsByUserIdAndTransactionTypeAndStatusAndAddress_JibunAddress(
            Long userId,
            TransactionType transactionType,
            PropertyStatus status,
            String jibunAddress
    );

    /**
     * 본인이 등록한 매물 목록 조회 (개인 분석 도구 성격상 마켓플레이스식 전체 조회가 아닌 본인 소유 매물만 대상).
     * 지역(주소 부분일치)/면적범위/거래유형/주택유형/보증금범위/월세범위 전부 선택 조건이라, null인
     * 파라미터는 조건 자체를 무시하도록 각 절을 "(:param IS NULL OR ...)" 형태로 구성했다.
     * monthlyRent는 전세 매물에서 항상 null이라 minMonthlyRent/maxMonthlyRent가 넘어오면 전세 매물은
     * 자연히 결과에서 제외된다(별도 분기 불필요).
     * 정렬은 메서드명이 아니라 Pageable의 Sort로 받는다 - 정렬 기준을 여러 개 허용하기 위함
     * (PageableUtils.validateSort로 허용된 필드인지 Service에서 먼저 검증한다).
     */
    @Query("""
            SELECT p FROM Property p
            LEFT JOIN p.address a
            WHERE p.userId = :userId
              AND p.status = :status
              AND (:region IS NULL OR a.roadAddress LIKE CONCAT('%', :region, '%')
                   OR a.jibunAddress LIKE CONCAT('%', :region, '%'))
              AND (:minArea IS NULL OR p.area >= :minArea)
              AND (:maxArea IS NULL OR p.area <= :maxArea)
              AND (:transactionType IS NULL OR p.transactionType = :transactionType)
              AND (:propertyType IS NULL OR p.propertyType = :propertyType)
              AND (:minDeposit IS NULL OR p.deposit >= :minDeposit)
              AND (:maxDeposit IS NULL OR p.deposit <= :maxDeposit)
              AND (:minMonthlyRent IS NULL OR p.monthlyRent >= :minMonthlyRent)
              AND (:maxMonthlyRent IS NULL OR p.monthlyRent <= :maxMonthlyRent)
            """)
    Page<Property> search(
            @Param("userId") Long userId,
            @Param("status") PropertyStatus status,
            @Param("region") String region,
            @Param("minArea") Double minArea,
            @Param("maxArea") Double maxArea,
            @Param("transactionType") TransactionType transactionType,
            @Param("propertyType") PropertyType propertyType,
            @Param("minDeposit") Long minDeposit,
            @Param("maxDeposit") Long maxDeposit,
            @Param("minMonthlyRent") Long minMonthlyRent,
            @Param("maxMonthlyRent") Long maxMonthlyRent,
            Pageable pageable
    );

    /**
     * 페이지네이션 없이 전체를 반환하는 버전 - checklist 도메인(ChecklistService.listMyChecklists)이
     * 매물별 체크리스트 현황을 한 번에 매칭해야 해서 여전히 이 형태로 사용 중이다. property 도메인
     * 자체의 목록조회(getMyProperties)는 위 search(...)를 쓴다.
     */
    List<Property> findAllByUserIdAndStatusOrderByCreatedAtDesc(Long userId, PropertyStatus status);
}
