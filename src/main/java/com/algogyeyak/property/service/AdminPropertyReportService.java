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

    /**
     * 알려진 한계(조회 후 저장 방식이라 원자적이지 않음): PropertyReport.transitionTo()의
     * RECEIVED 상태 가드는 메모리상의 값만 확인하므로, 관리자 두 명이 같은 신고를 동시에 열어
     * 서로 다른 결과(RESOLVED/REJECTED)로 처리하면 둘 다 그 가드를 통과해 나중에 커밋한 쪽이
     * 조용히 덮어쓸 수 있다. AdminChecklistTemplateService.validateCode와 같은 이유로
     * 감수하기로 함(관리자 전용 화면, 동시 처리 빈도 매우 낮음, 데이터 손상이 아니라 검토
     * 결과 하나가 재확정되는 정도) - 실제로 강한 불변식이 필요해지면 @Version(낙관적 락)이나
     * 비관적 락이 필요하지만, 지금은 그 정도의 스키마/락 복잡도를 들일 만큼의 위험이 아니라고
     * 판단했다.
     */
    @Transactional
    public AdminPropertyReportDetailResponse review(Long reviewerId, Long reportId, PropertyReportStatus status, String memo) {
        if (status != PropertyReportStatus.RESOLVED && status != PropertyReportStatus.REJECTED) {
            throw new BusinessException(ErrorCode.ADMIN_INVALID_STATUS_TRANSITION, "RESOLVED/REJECTED로만 처리할 수 있습니다.");
        }

        PropertyReport report = findReport(reportId);
        // PropertyReport는 매물 소유자 본인이 직접 등록하는 자가 신고다(클래스 주석 참고) - 그
        // 소유자가 ADMIN 권한도 갖고 있으면 아무 제약 없이는 자기 매물에 대한 자신의 신고를
        // 스스로 검토/확정할 수 있어, AdminUserController.rejectSelf와 같은 이유로 이 경로도
        // 막아야 한다(신고 검토는 제3자 확인이 전제인 절차라 본인 확정은 그 절차를 무력화한다).
        if (report.getReporterId().equals(reviewerId)) {
            throw new BusinessException(ErrorCode.ADMIN_PROPERTY_REPORT_SELF_REVIEW);
        }
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
