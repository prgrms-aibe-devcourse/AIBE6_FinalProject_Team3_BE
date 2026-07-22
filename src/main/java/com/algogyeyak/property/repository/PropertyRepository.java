package com.algogyeyak.property.repository;

import com.algogyeyak.property.entity.Property;
import com.algogyeyak.property.entity.PropertyStatus;
import com.algogyeyak.property.entity.TransactionType;
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
}
