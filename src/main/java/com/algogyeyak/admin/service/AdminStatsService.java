package com.algogyeyak.admin.service;

import com.algogyeyak.admin.dto.AdminDashboardStatsResponse;
import com.algogyeyak.admin.dto.AdminStatsDistributionResponse;
import com.algogyeyak.admin.dto.AdminStatsSummaryResponse;
import com.algogyeyak.admin.dto.AdminStatsTrendResponse;
import com.algogyeyak.property.entity.PropertyReportReason;
import com.algogyeyak.property.entity.PropertyReportStatus;
import com.algogyeyak.property.entity.PropertyStatus;
import com.algogyeyak.property.repository.PropertyRepository;
import com.algogyeyak.property.repository.PropertyReportRepository;
import com.algogyeyak.user.repository.UserRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;
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

    private static final int TREND_DAYS = 14;

    private final UserRepository userRepository;
    private final PropertyRepository propertyRepository;
    private final PropertyReportRepository propertyReportRepository;

    public AdminDashboardStatsResponse getDashboard() {
        return new AdminDashboardStatsResponse(summary(), trends(), distributions());
    }

    private AdminStatsSummaryResponse summary() {
        return new AdminStatsSummaryResponse(
                userRepository.count(),
                propertyRepository.countByStatus(PropertyStatus.ACTIVE),
                propertyReportRepository.countByStatus(PropertyReportStatus.RECEIVED));
    }

    private AdminStatsTrendResponse trends() {
        LocalDateTime since = LocalDate.now().minusDays(TREND_DAYS - 1L).atStartOfDay();
        return new AdminStatsTrendResponse(
                toDailyCounts(userRepository.findCreatedAtSince(since)),
                toDailyCounts(propertyRepository.findCreatedAtSince(since)));
    }

    // 최근 TREND_DAYS일을 하루도 빠짐없이 채운다 - 특정 날짜에 가입/등록이 0건이어도 프론트 차트가
    // 그 날짜를 건너뛰지 않고 0으로 그릴 수 있어야 하기 때문이다.
    private List<AdminStatsTrendResponse.Point> toDailyCounts(List<LocalDateTime> timestamps) {
        Map<LocalDate, Long> counts = timestamps.stream()
                .collect(Collectors.groupingBy(LocalDateTime::toLocalDate, Collectors.counting()));

        LocalDate start = LocalDate.now().minusDays(TREND_DAYS - 1L);
        return IntStream.range(0, TREND_DAYS)
                .mapToObj(start::plusDays)
                .map(date -> new AdminStatsTrendResponse.Point(date, counts.getOrDefault(date, 0L)))
                .toList();
    }

    private AdminStatsDistributionResponse distributions() {
        long registeredCount = propertyRepository.countDistinctUserId();
        long unregisteredCount = userRepository.count() - registeredCount;
        List<AdminStatsDistributionResponse.PropertyRegistrationCount> byPropertyRegistration = List.of(
                new AdminStatsDistributionResponse.PropertyRegistrationCount(true, registeredCount),
                new AdminStatsDistributionResponse.PropertyRegistrationCount(false, unregisteredCount));

        List<AdminStatsDistributionResponse.ReportReasonCount> byReportReason = mapToCounts(
                PropertyReportReason.values(), propertyReportRepository::countByReason,
                AdminStatsDistributionResponse.ReportReasonCount::new);

        return new AdminStatsDistributionResponse(byPropertyRegistration, byReportReason);
    }

    private <E, R> List<R> mapToCounts(E[] values, Function<E, Long> counter, BiFunction<E, Long, R> toResult) {
        return Arrays.stream(values)
                .map(value -> toResult.apply(value, counter.apply(value)))
                .toList();
    }
}
