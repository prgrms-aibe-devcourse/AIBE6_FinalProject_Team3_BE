package com.algogyeyak.property.service;

import com.algogyeyak.global.error.ErrorCode;
import com.algogyeyak.global.exception.BusinessException;
import com.algogyeyak.property.dto.PropertyReportRequest;
import com.algogyeyak.property.dto.PropertyReportResponse;
import com.algogyeyak.property.entity.Property;
import com.algogyeyak.property.entity.PropertyReport;
import com.algogyeyak.property.entity.PropertyReportReason;
import com.algogyeyak.property.repository.PropertyReportRepository;
import com.algogyeyak.property.repository.PropertyRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 매물 신고. 마켓플레이스식 "타인 매물 신고"가 아니라 본인이 등록한 매물을 본인이 직접
 * 신고하는 자가 플래그 구조라, 소유권 검증(PROPERTY_ACCESS_DENIED)을 기존 Property
 * 조회/수정/삭제와 동일하게 적용한다. risk-analysis 도메인의 시스템 자동 중복탐지와는
 * 역할이 구분되는 사용자 제보 기반 데이터라 별도 서비스/테이블로 둔다.
 */
@Service
@Transactional(readOnly = true)
public class PropertyReportService {

    // "기타" 사유 등 자유 입력 상세 내용의 최대 길이. 요구사항의 "기타 사유 입력값 길이 초과" 실패
    // 케이스가 실제로는 발생하지 않는 죽은 케이스였어서, 실제로 검증하도록 추가했다.
    private static final int MAX_DETAIL_LENGTH = 500;

    private final PropertyRepository propertyRepository;
    private final PropertyReportRepository propertyReportRepository;
    private final TransactionTemplate requiresNewTransactionTemplate;

    public PropertyReportService(
            PropertyRepository propertyRepository,
            PropertyReportRepository propertyReportRepository,
            PlatformTransactionManager transactionManager) {
        this.propertyRepository = propertyRepository;
        this.propertyReportRepository = propertyReportRepository;
        this.requiresNewTransactionTemplate = new TransactionTemplate(transactionManager);
        this.requiresNewTransactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Transactional
    public PropertyReportResponse report(Long userId, Long propertyId, PropertyReportRequest request) {
        Property property = propertyRepository.findById(propertyId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PROPERTY_NOT_FOUND));

        if (property.isDeleted()) {
            throw new BusinessException(ErrorCode.PROPERTY_NOT_FOUND);
        }

        if (!property.isOwnedBy(userId)) {
            throw new BusinessException(ErrorCode.PROPERTY_ACCESS_DENIED);
        }

        if (request.reason() == null) {
            throw new BusinessException(ErrorCode.REPORT_REASON_REQUIRED);
        }

        if (request.reason() == PropertyReportReason.ETC
                && (request.detail() == null || request.detail().isBlank())) {
            throw new BusinessException(ErrorCode.REPORT_DETAIL_REQUIRED);
        }

        // detail은 ETC가 아닌 사유면 엔티티 생성자에서 어차피 null로 버려지므로, 그 경우까지
        // 길이 초과로 막을 필요는 없다 - 실제로 저장/사용되는 ETC 케이스만 검증한다.
        if (request.reason() == PropertyReportReason.ETC && request.detail().length() > MAX_DETAIL_LENGTH) {
            throw new BusinessException(ErrorCode.REPORT_DETAIL_TOO_LONG);
        }

        if (propertyReportRepository.existsByPropertyIdAndReporterId(propertyId, userId)) {
            throw new BusinessException(ErrorCode.REPORT_DUPLICATE);
        }

        PropertyReport report = PropertyReport.builder()
                .propertyId(propertyId)
                .reporterId(userId)
                .reason(request.reason())
                .detail(request.detail())
                .build();

        try {
            // existsByPropertyIdAndReporterId 체크와 실제 insert 사이의 동시 요청 레이스(빠른 중복
            // 클릭 등)는 위 체크만으로 막을 수 없다 - (property_id, reporter_id) DB 유니크 제약이
            // 최종 방어선이다. saveAndFlush를 REQUIRES_NEW로 분리하는 이유는 LocalAuthService.signup()과
            // 동일하다 - 같은 세션에서 유니크 제약 위반 후 그 세션으로 쿼리를 이어가면 Hibernate가
            // "세션이 예외 이후 flush됨(AssertionFailure)"을 던진다.
            requiresNewTransactionTemplate.executeWithoutResult(status -> propertyReportRepository.saveAndFlush(report));
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(ErrorCode.REPORT_DUPLICATE);
        }

        return PropertyReportResponse.from(report);
    }
}
