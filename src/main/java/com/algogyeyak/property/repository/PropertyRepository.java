package com.algogyeyak.property.repository;

import com.algogyeyak.property.entity.Property;
import com.algogyeyak.property.entity.PropertyStatus;
import com.algogyeyak.property.entity.PropertyType;
import com.algogyeyak.property.entity.TransactionType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

public interface PropertyRepository extends JpaRepository<Property, Long> {

    /**
     * risk-analysis/deposit-safety의 신호 upsert(findByPropertyIdAndSignalType 등)가 "없으면 insert,
     * 있으면 update"인 check-then-act라서, 같은 매물에 대한 두 요청(예: 위험 신호 재계산 POST가
     * 빠르게 두 번 겹치는 경우, 또는 사용자 조회 트리거와 PropertyUpdatedEvent 배치 재계산이 동시에
     * 도는 경우)이 동시에 이 메서드를 타면 둘 다 "없음"으로 보고 동시에 insert를 시도해 유니크 제약
     * 위반(DataIntegrityViolationException)이 날 수 있다. 이 조회로 매물 행에 쓰기 잠금을 걸어두면,
     * 뒤에 도착한 트랜잭션은 앞선 트랜잭션이 커밋할 때까지 대기했다가 그제서야 "이미 있음"을 보고
     * update로 처리되어 위반이 사라진다 - FakeListingSignalService.checkAndSave(Property)/
     * DepositSafetyCheckService.calculate() 진입부에서 호출한다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Property p where p.id = :id")
    Optional<Property> findByIdForRiskCheckUpdate(@Param("id") Long id);

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
     * risk-analysis의 중복매물 탐지용 - 위 두 메서드와 달리 userId 조건이 없다(다른 계정이 올린
     * 매물도 잡아야 함). 본인 자신은 idNot으로 제외한다.
     */
    boolean existsByIdNotAndTransactionTypeAndStatusAndAddress_RoadAddress(
            Long id,
            TransactionType transactionType,
            PropertyStatus status,
            String roadAddress
    );

    boolean existsByIdNotAndTransactionTypeAndStatusAndAddress_JibunAddress(
            Long id,
            TransactionType transactionType,
            PropertyStatus status,
            String jibunAddress
    );

    /**
     * risk-analysis의 동일계정 다수등록 탐지용 - 특정 유저가 기준 시각 이후 등록한 활성 매물 전체.
     */
    List<Property> findAllByUserIdAndStatusAndCreatedAtAfter(
            Long userId,
            PropertyStatus status,
            LocalDateTime after
    );

    /**
     * risk-analysis의 짧은 주기 재등록 탐지용 - 동일 유저가 논리 삭제한 매물 중, 기준 시각 이후
     * (최근에) 삭제된 것만 동일 주소로 찾는다. 별도 deletedAt 컬럼이 없어 updatedAt을 삭제 시각으로
     * 대신 쓴다(delete()가 상태를 바꾸는 마지막 변경이라는 전제).
     */
    List<Property> findAllByUserIdAndStatusAndAddress_RoadAddressAndUpdatedAtAfter(
            Long userId,
            PropertyStatus status,
            String roadAddress,
            LocalDateTime after
    );

    List<Property> findAllByUserIdAndStatusAndAddress_JibunAddressAndUpdatedAtAfter(
            Long userId,
            PropertyStatus status,
            String jibunAddress,
            LocalDateTime after
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

    // 관리자 통계 대시보드: 기간별 등록 추이용. UserRepository.findCreatedAtBetween과 동일한 이유로
    // DB별 날짜 절삭 함수 대신 원본 시각만 가져와 서비스에서 집계한다. 이후 삭제된 매물도 등록
    // "발생" 자체는 그대로 집계에 포함되어야 하므로 status로 필터링하지 않는다.
    @Query("SELECT p.createdAt FROM Property p WHERE p.createdAt >= :start AND p.createdAt < :end")
    List<LocalDateTime> findCreatedAtBetween(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    // 관리자 통계 대시보드: 신규 등록 매물 수 카드용(기간 내 등록 + 활성 상태).
    long countByStatusAndCreatedAtBetween(PropertyStatus status, LocalDateTime start, LocalDateTime end);

    // 관리자 통계 대시보드: 매물 등록자/미등록자 분포용 - 주어진 유저 id들 중 매물을 1건이라도
    // 등록한 유저가 몇 명인지. 가입 시점과 무관하게 "등록한 적이 있는지"를 기준으로 삼으므로(가입은
    // 기간 내지만 등록은 그 이후일 수도 있음) status/기간으로 다시 필터링하지 않는다. userIds가
    // 비어 있으면 호출부(AdminStatsService)에서 쿼리 자체를 건너뛴다.
    @Query("SELECT COUNT(DISTINCT p.userId) FROM Property p WHERE p.userId IN :userIds")
    long countDistinctUserIdIn(@Param("userIds") List<Long> userIds);
}
