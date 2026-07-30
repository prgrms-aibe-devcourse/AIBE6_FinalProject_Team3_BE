package com.algogyeyak.property.repository;

import com.algogyeyak.property.entity.Property;
import com.algogyeyak.property.entity.PropertyStatus;
import com.algogyeyak.property.entity.TransactionType;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

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
     * 정렬은 메서드명이 아니라 Pageable의 Sort로 받는다 - 정렬 기준을 여러 개 허용하기 위함
     * (PageableUtils.validateSort로 허용된 필드인지 Service에서 먼저 검증한다).
     */
    Page<Property> findAllByUserIdAndStatus(Long userId, PropertyStatus status, Pageable pageable);

    /**
     * 페이지네이션 없이 전체를 반환하는 버전 - checklist 도메인(ChecklistService.listMyChecklists)이
     * 매물별 체크리스트 현황을 한 번에 매칭해야 해서 여전히 이 형태로 사용 중이다. property 도메인
     * 자체의 목록조회(getMyProperties)는 위 findAllByUserIdAndStatus(Pageable)를 쓴다.
     */
    List<Property> findAllByUserIdAndStatusOrderByCreatedAtDesc(Long userId, PropertyStatus status);
}
