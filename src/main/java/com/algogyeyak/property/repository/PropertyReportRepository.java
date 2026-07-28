package com.algogyeyak.property.repository;

import com.algogyeyak.property.entity.PropertyReport;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PropertyReportRepository extends JpaRepository<PropertyReport, Long> {

    /**
     * 동일 사용자가 동일 매물을 이미 신고했는지 확인 (REPORT_DUPLICATE 판단용).
     */
    boolean existsByPropertyIdAndReporterId(Long propertyId, Long reporterId);
}
