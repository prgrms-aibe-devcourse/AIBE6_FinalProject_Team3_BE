package com.algogyeyak.property.service;

import com.algogyeyak.global.error.ErrorCode;
import com.algogyeyak.global.exception.BusinessException;
import com.algogyeyak.global.pagination.PageableUtils;
import com.algogyeyak.global.response.PageResponse;
import com.algogyeyak.property.dto.AdminPropertyReportDetailResponse;
import com.algogyeyak.property.dto.AdminPropertyReportListItemResponse;
import com.algogyeyak.property.dto.PropertyReportSearchCondition;
import com.algogyeyak.property.entity.Property;
import com.algogyeyak.property.entity.PropertyReport;
import com.algogyeyak.property.entity.PropertyReportStatus;
import com.algogyeyak.property.repository.PropertyReportRepository;
import com.algogyeyak.property.repository.PropertyRepository;
import com.algogyeyak.user.entity.User;
import com.algogyeyak.user.repository.UserRepository;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * PropertyReport(사용자 자가 신고)와 Property/User는 JPA 연관관계가 아니라 순수 FK 컬럼으로만
 * 연결되어 있다(PropertyReport 엔티티 주석 참고 - risk-analysis 도메인과 의도적으로 분리된 설계).
 * 그래서 목록/상세 조회 시 이 서비스가 직접 배치 조회해 응답에 필요한 정보를 합성한다.
 */
@Service
@RequiredArgsConstructor
public class AdminPropertyReportService {

    private static final Set<String> ALLOWED_SORT_PROPERTIES = Set.of("createdAt");

    private final PropertyReportRepository propertyReportRepository;
    private final PropertyRepository propertyRepository;
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public PageResponse<AdminPropertyReportListItemResponse> list(Pageable pageable, PropertyReportSearchCondition condition) {
        PageableUtils.validateSort(pageable, ALLOWED_SORT_PROPERTIES);
        PageableUtils.validateMaxSize(pageable);

        Page<PropertyReport> page = propertyReportRepository.search(condition.status(), condition.reason(), pageable);
        Map<Long, Property> properties = fetchByIds(page.getContent(), PropertyReport::getPropertyId, propertyRepository::findAllById, Property::getId);
        Map<Long, User> reporters = fetchByIds(page.getContent(), PropertyReport::getReporterId, userRepository::findAllById, User::getId);

        return PageResponse.from(page, report -> AdminPropertyReportListItemResponse.of(
                report, properties.get(report.getPropertyId()), reporters.get(report.getReporterId())));
    }

    @Transactional(readOnly = true)
    public AdminPropertyReportDetailResponse getDetail(Long reportId) {
        PropertyReport report = findReport(reportId);
        return toDetailResponse(report);
    }

    @Transactional
    public AdminPropertyReportDetailResponse review(Long reviewerId, Long reportId, PropertyReportStatus status, String memo) {
        if (status != PropertyReportStatus.RESOLVED && status != PropertyReportStatus.REJECTED) {
            throw new BusinessException(ErrorCode.ADMIN_INVALID_STATUS_TRANSITION, "RESOLVED/REJECTED로만 처리할 수 있습니다.");
        }

        PropertyReport report = findReport(reportId);
        if (status == PropertyReportStatus.RESOLVED) {
            report.resolve(reviewerId, memo);
        } else {
            report.reject(reviewerId, memo);
        }

        return toDetailResponse(report);
    }

    private AdminPropertyReportDetailResponse toDetailResponse(PropertyReport report) {
        Property property = propertyRepository.findById(report.getPropertyId()).orElse(null);
        User reporter = userRepository.findById(report.getReporterId()).orElse(null);
        return AdminPropertyReportDetailResponse.of(report, property, reporter);
    }

    private PropertyReport findReport(Long reportId) {
        return propertyReportRepository.findById(reportId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ADMIN_PROPERTY_REPORT_NOT_FOUND));
    }

    private <E> Map<Long, E> fetchByIds(
            List<PropertyReport> reports,
            Function<PropertyReport, Long> idExtractor,
            Function<List<Long>, List<E>> batchFinder,
            Function<E, Long> entityIdExtractor
    ) {
        List<Long> ids = reports.stream().map(idExtractor).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return batchFinder.apply(ids).stream().collect(Collectors.toMap(entityIdExtractor, e -> e));
    }
}
