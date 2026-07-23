package com.algogyeyak.property.repository;

import com.algogyeyak.property.entity.Property;
import com.algogyeyak.property.entity.PropertyStatus;
import com.algogyeyak.property.entity.TransactionType;
import java.util.List;
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
     * 본인이 등록한 매물 목록 조회 (개인 분석 도구 성격상 마켓플레이스식 전체 조회가 아닌 본인 소유 매물만 대상).
     */
    List<Property> findAllByUserIdAndStatusOrderByCreatedAtDesc(Long userId, PropertyStatus status);
}
