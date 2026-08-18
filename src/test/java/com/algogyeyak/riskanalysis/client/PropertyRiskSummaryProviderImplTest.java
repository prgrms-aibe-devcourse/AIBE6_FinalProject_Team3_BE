package com.algogyeyak.riskanalysis.client;

import com.algogyeyak.property.entity.Property;
import com.algogyeyak.property.entity.PropertyType;
import com.algogyeyak.property.entity.TransactionType;
import com.algogyeyak.riskanalysis.dto.PropertyRiskSummary;
import com.algogyeyak.riskanalysis.entity.DepositSafetyCheck;
import com.algogyeyak.riskanalysis.entity.PropertyRisk;
import com.algogyeyak.riskanalysis.entity.PropertyRiskCheck;
import com.algogyeyak.riskanalysis.enums.DepositSafetyCheckReason;
import com.algogyeyak.riskanalysis.enums.RiskCheckStatus;
import com.algogyeyak.riskanalysis.enums.RiskSignalType;
import com.algogyeyak.riskanalysis.repository.DepositSafetyCheckRepository;
import com.algogyeyak.riskanalysis.repository.PropertyRiskCheckRepository;
import com.algogyeyak.riskanalysis.repository.PropertyRiskRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("PropertyRiskSummaryProviderImpl")
class PropertyRiskSummaryProviderImplTest {

    private final PropertyRiskRepository propertyRiskRepository = mock(PropertyRiskRepository.class);
    private final PropertyRiskCheckRepository propertyRiskCheckRepository = mock(PropertyRiskCheckRepository.class);
    private final DepositSafetyCheckRepository depositSafetyCheckRepository = mock(DepositSafetyCheckRepository.class);
    private final PropertyRiskSummaryProviderImpl provider = new PropertyRiskSummaryProviderImpl(
            propertyRiskRepository, propertyRiskCheckRepository, depositSafetyCheckRepository);

    private Property property(Long id) {
        Property property = Property.builder()
                .userId(1L)
                .title("테스트 매물")
                .propertyType(PropertyType.OFFICETEL)
                .transactionType(TransactionType.JEONSE)
                .deposit(100_000_000L)
                .area(20.0)
                .build();
        ReflectionTestUtils.setField(property, "id", id);
        return property;
    }

    @Test
    @DisplayName("위험신호를 한 번도 체크 안 한 매물은 결과 맵에 나타나지 않는다")
    void propertyNeverCheckedIsAbsentFromResult() {
        when(propertyRiskCheckRepository.findAllByProperty_UserId(1L)).thenReturn(List.of());
        when(propertyRiskRepository.findAllByProperty_UserId(1L)).thenReturn(List.of());
        when(depositSafetyCheckRepository.findAllByProperty_UserId(1L)).thenReturn(List.of());

        Map<Long, PropertyRiskSummary> result = provider.getSummariesByUserId(1L);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("체크는 했지만 발견된 신호가 없으면 checkSignalCount 0을 담고 signalSummary는 null이다")
    void checkedButNoRiskFoundHasZeroCount() {
        Property property = property(10L);
        PropertyRiskCheck check = PropertyRiskCheck.success(property, RiskSignalType.DUPLICATE_LISTING, "v1.0");
        when(propertyRiskCheckRepository.findAllByProperty_UserId(1L)).thenReturn(List.of(check));
        when(propertyRiskRepository.findAllByProperty_UserId(1L)).thenReturn(List.of());
        when(depositSafetyCheckRepository.findAllByProperty_UserId(1L)).thenReturn(List.of());

        Map<Long, PropertyRiskSummary> result = provider.getSummariesByUserId(1L);

        PropertyRiskSummary summary = result.get(10L);
        assertThat(summary.checkSignalCount()).isEqualTo(0);
        assertThat(summary.signalSummary()).isNull();
        assertThat(summary.jeonseRatio()).isNull();
    }

    @Test
    @DisplayName("발견된 신호가 있으면 개수와 설명을 이어붙인 요약을 담는다")
    void risksFoundIncludeCountAndJoinedSummary() {
        Property property = property(10L);
        PropertyRiskCheck check = PropertyRiskCheck.success(property, RiskSignalType.DUPLICATE_LISTING, "v1.0");
        PropertyRisk riskA = PropertyRisk.of(property, RiskSignalType.DUPLICATE_LISTING, "동일 주소로 등록된 다른 매물이 있어요");
        PropertyRisk riskB = PropertyRisk.of(property, RiskSignalType.PRICE_ANOMALY, "시세보다 20% 낮은 가격이에요");
        when(propertyRiskCheckRepository.findAllByProperty_UserId(1L)).thenReturn(List.of(check));
        when(propertyRiskRepository.findAllByProperty_UserId(1L)).thenReturn(List.of(riskA, riskB));
        when(depositSafetyCheckRepository.findAllByProperty_UserId(1L)).thenReturn(List.of());

        Map<Long, PropertyRiskSummary> result = provider.getSummariesByUserId(1L);

        PropertyRiskSummary summary = result.get(10L);
        assertThat(summary.checkSignalCount()).isEqualTo(2);
        assertThat(summary.signalSummary()).isEqualTo("동일 주소로 등록된 다른 매물이 있어요, 시세보다 20% 낮은 가격이에요");
    }

    @Test
    @DisplayName("전세가율 계산이 CALCULATED 상태일 때만 jeonseRatio 값을 담는다")
    void jeonseRatioOnlyPresentWhenCalculated() {
        Property calculatedProperty = property(10L);
        Property unavailableProperty = property(20L);
        DepositSafetyCheck calculated = DepositSafetyCheck.calculated(
                calculatedProperty, BigDecimal.valueOf(82), null, null, LocalDate.of(2026, 7, 31), "설명", 5, 300, "v1.0");
        DepositSafetyCheck unavailable = DepositSafetyCheck.unavailable(
                unavailableProperty, null, null, DepositSafetyCheckReason.DEPOSIT_INFO_MISSING, "v1.0");
        when(propertyRiskCheckRepository.findAllByProperty_UserId(1L)).thenReturn(List.of());
        when(propertyRiskRepository.findAllByProperty_UserId(1L)).thenReturn(List.of());
        when(depositSafetyCheckRepository.findAllByProperty_UserId(1L)).thenReturn(List.of(calculated, unavailable));

        Map<Long, PropertyRiskSummary> result = provider.getSummariesByUserId(1L);

        assertThat(result.get(10L).jeonseRatio()).isEqualTo(82);
        assertThat(result).doesNotContainKey(20L);
    }
}
