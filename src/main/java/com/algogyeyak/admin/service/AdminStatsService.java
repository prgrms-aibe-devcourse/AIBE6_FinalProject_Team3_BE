package com.algogyeyak.admin.service;

import com.algogyeyak.admin.dto.AdminDashboardStatsResponse;
import com.algogyeyak.admin.dto.AdminStatsDistributionResponse;
import com.algogyeyak.admin.dto.AdminStatsSummaryResponse;
import com.algogyeyak.admin.dto.AdminStatsTrendResponse;
import com.algogyeyak.global.error.ErrorCode;
import com.algogyeyak.global.exception.BusinessException;
import com.algogyeyak.property.entity.PropertyReportReason;
import com.algogyeyak.property.entity.PropertyReportStatus;
import com.algogyeyak.property.repository.PropertyRepository;
import com.algogyeyak.property.repository.PropertyReportRepository;
import com.algogyeyak.user.repository.UserRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 도메인(user/property)을 횡단 집계하는 통계라 특정 도메인 패키지에 두지 않고 admin 패키지에서 조합한다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminStatsService {

    private static final int DEFAULT_TREND_DAYS = 14;

    // 하루씩 채워 넣는 toDailyCounts 특성상 기간이 너무 길면 응답 포인트 수가 과도해진다 - 관리자가
    // 조회 기간을 직접 고를 수 있게 된 만큼 상한을 둔다.
    private static final int MAX_TREND_DAYS = 90;

    private final UserRepository userRepository;
    private final PropertyRepository propertyRepository;
    private final PropertyReportRepository propertyReportRepository;

    public AdminDashboardStatsResponse getDashboard(LocalDate startDate, LocalDate endDate) {
        LocalDate resolvedEnd = endDate != null ? endDate : LocalDate.now();
        LocalDate resolvedStart = startDate != null ? startDate : resolvedEnd.minusDays(DEFAULT_TREND_DAYS - 1L);
        validateRange(resolvedStart, resolvedEnd);

        return new AdminDashboardStatsResponse(
                summary(resolvedStart, resolvedEnd),
                trends(resolvedStart, resolvedEnd),
                distributions(resolvedStart, resolvedEnd));
    }

    private void validateRange(LocalDate start, LocalDate end) {
        if (start.isAfter(end)) {
            throw new BusinessException(ErrorCode.ADMIN_INVALID_DATE_RANGE, "조회 시작일은 종료일보다 이후일 수 없습니다.");
        }
        if (ChronoUnit.DAYS.between(start, end) + 1 > MAX_TREND_DAYS) {
            throw new BusinessException(ErrorCode.ADMIN_INVALID_DATE_RANGE, "조회 기간은 최대 " + MAX_TREND_DAYS + "일까지 가능합니다.");
        }
    }

    // 요약 카드 3개 전부 "선택한 기간 동안 발생한" 건수다(예: 총 회원수 → 기간 내 신규 가입자 수) -
    // 상단 요약이 아래 추이 차트와 다른 기준(전체 누적)을 쓰면 같은 화면에서 숫자가 서로 안 맞아 보인다.
    // newProperties는 특히 trends().propertyRegistrations와 같은 기준(status 무관, 등록 "발생" 자체)을
    // 써야 한다 - 예전엔 여기만 ACTIVE로 필터링해서, 기간 내 등록 후 삭제된 매물이 있으면 추이
    // 차트의 합계보다 이 카드가 더 작게 나와 같은 화면에서 숫자가 어긋났다.
    private AdminStatsSummaryResponse summary(LocalDate start, LocalDate end) {
        LocalDateTime rangeStart = start.atStartOfDay();
        LocalDateTime rangeEnd = end.plusDays(1).atStartOfDay();
        return new AdminStatsSummaryResponse(
                userRepository.countByCreatedAtBetween(rangeStart, rangeEnd),
                propertyRepository.countByCreatedAtBetween(rangeStart, rangeEnd),
                propertyReportRepository.countByStatusAndCreatedAtBetween(PropertyReportStatus.RECEIVED, rangeStart, rangeEnd));
    }

    private AdminStatsTrendResponse trends(LocalDate start, LocalDate end) {
        LocalDateTime rangeStart = start.atStartOfDay();
        LocalDateTime rangeEnd = end.plusDays(1).atStartOfDay();
        return new AdminStatsTrendResponse(
                toDailyCounts(userRepository.findCreatedAtBetween(rangeStart, rangeEnd), start, end),
                toDailyCounts(propertyRepository.findCreatedAtBetween(rangeStart, rangeEnd), start, end));
    }

    // 조회 기간을 하루도 빠짐없이 채운다 - 특정 날짜에 가입/등록이 0건이어도 프론트 차트가 그 날짜를
    // 건너뛰지 않고 0으로 그릴 수 있어야 하기 때문이다.
    private List<AdminStatsTrendResponse.Point> toDailyCounts(List<LocalDateTime> timestamps, LocalDate start, LocalDate end) {
        Map<LocalDate, Long> counts = timestamps.stream()
                .collect(Collectors.groupingBy(LocalDateTime::toLocalDate, Collectors.counting()));

        long days = ChronoUnit.DAYS.between(start, end) + 1;
        return IntStream.range(0, (int) days)
                .mapToObj(start::plusDays)
                .map(date -> new AdminStatsTrendResponse.Point(date, counts.getOrDefault(date, 0L)))
                .toList();
    }

    // byPropertyRegistration은 선택한 기간 내 가입자를 모집단으로 하는 가입→등록 전환율이다 -
    // summary()의 신규 가입자 수와 항상 합이 맞아야 하므로 동일하게 기간 내 가입자 id로 모집단을
    // 고정하고, 등록 여부는 가입 시점 이후 언제든(기간 밖이어도) 매물을 등록했는지로 판단한다.
    // byReportReason은 요약 카드와 동일하게 기간 내 접수 건만 집계한다.
    private AdminStatsDistributionResponse distributions(LocalDate start, LocalDate end) {
        LocalDateTime rangeStart = start.atStartOfDay();
        LocalDateTime rangeEnd = end.plusDays(1).atStartOfDay();

        List<Long> userIdsJoinedInRange = userRepository.findIdsByCreatedAtBetween(rangeStart, rangeEnd);
        long registeredCount = userIdsJoinedInRange.isEmpty()
                ? 0L
                : propertyRepository.countDistinctUserIdIn(userIdsJoinedInRange);
        long unregisteredCount = userIdsJoinedInRange.size() - registeredCount;
        List<AdminStatsDistributionResponse.PropertyRegistrationCount> byPropertyRegistration = List.of(
                new AdminStatsDistributionResponse.PropertyRegistrationCount(true, registeredCount),
                new AdminStatsDistributionResponse.PropertyRegistrationCount(false, unregisteredCount));

        Map<PropertyReportReason, Long> reasonCounts = propertyReportRepository
                .countGroupedByReasonAndCreatedAtBetween(rangeStart, rangeEnd).stream()
                .collect(Collectors.toMap(
                        PropertyReportRepository.ReasonCount::getReason, PropertyReportRepository.ReasonCount::getCount));
        // GROUP BY 결과에는 해당 기간에 실제로 접수된 사유만 나타나므로, 나머지 사유는 0건으로
        // 채워야 이전과 동일하게 모든 사유가 응답에 나타난다(프론트가 이 목록을 그대로 렌더링).
        List<AdminStatsDistributionResponse.ReportReasonCount> byReportReason = Arrays.stream(PropertyReportReason.values())
                .map(reason -> new AdminStatsDistributionResponse.ReportReasonCount(
                        reason, reasonCounts.getOrDefault(reason, 0L)))
                .toList();

        return new AdminStatsDistributionResponse(byPropertyRegistration, byReportReason);
    }
}
