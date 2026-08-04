package com.algogyeyak.riskanalysis.service;

import com.algogyeyak.property.entity.Property;
import com.algogyeyak.property.entity.PropertyType;
import com.algogyeyak.property.entity.TransactionType;
import com.algogyeyak.riskanalysis.client.MarketSaleDataClient;
import com.algogyeyak.riskanalysis.dto.MarketSalePrice;
import com.algogyeyak.riskanalysis.entity.DepositSafetyCheck;
import com.algogyeyak.riskanalysis.enums.DepositSafetyCheckReason;
import com.algogyeyak.riskanalysis.enums.DepositSafetyStatus;
import com.algogyeyak.riskanalysis.policy.RiskPolicyConfig;
import com.algogyeyak.riskanalysis.repository.DepositSafetyCheckRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("DepositSafetyCheckService")
class DepositSafetyCheckServiceTest {

    private final DepositSafetyCheckRepository depositSafetyCheckRepository = mock(DepositSafetyCheckRepository.class);
    private final MarketSaleDataClient marketSaleDataClient = mock(MarketSaleDataClient.class);
    private final RiskPolicyConfig policyConfig = new RiskPolicyConfig();
    private final DepositSafetyCheckService service =
            new DepositSafetyCheckService(depositSafetyCheckRepository, marketSaleDataClient, policyConfig);

    private Property property(Long id, TransactionType transactionType, Long deposit) {
        Property property = Property.builder()
                .userId(1L)
                .propertyType(PropertyType.OFFICETEL)
                .transactionType(transactionType)
                .deposit(deposit)
                .area(20.0)
                .build();
        ReflectionTestUtils.setField(property, "id", id);
        return property;
    }

    private void setPolicy() {
        ReflectionTestUtils.setField(policyConfig, "version", "v1.0");
        ReflectionTestUtils.setField(policyConfig, "jeonseRatioCautionFrom", 80);
        ReflectionTestUtils.setField(policyConfig, "jeonseRatioWarnFrom", 100);
        ReflectionTestUtils.setField(policyConfig, "jeonseRatioWarnTo", 150);
        ReflectionTestUtils.setField(policyConfig, "jeonseRatioAlertOver", 150);
    }

    @Test
    @DisplayName("월세 매물은 시세 조회 없이 TRANSACTION_TYPE_UNSUPPORTED로 판정불가 처리한다")
    void monthlyRentIsUnavailableWithoutMarketLookup() {
        setPolicy();
        Property property = property(10L, TransactionType.MONTHLY_RENT, 10_000_000L);
        when(depositSafetyCheckRepository.findByPropertyId(10L)).thenReturn(Optional.empty());

        service.checkAndSave(property);

        var captor = org.mockito.ArgumentCaptor.forClass(DepositSafetyCheck.class);
        verify(depositSafetyCheckRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(DepositSafetyStatus.UNAVAILABLE);
        assertThat(captor.getValue().getReason()).isEqualTo(DepositSafetyCheckReason.TRANSACTION_TYPE_UNSUPPORTED);
        verifyNoMarketLookup();
    }

    @Test
    @DisplayName("보증금 정보가 없으면 DEPOSIT_INFO_MISSING으로 판정불가 처리한다")
    void missingDepositIsUnavailable() {
        setPolicy();
        Property property = property(10L, TransactionType.JEONSE, null);
        when(depositSafetyCheckRepository.findByPropertyId(10L)).thenReturn(Optional.empty());

        service.checkAndSave(property);

        var captor = org.mockito.ArgumentCaptor.forClass(DepositSafetyCheck.class);
        verify(depositSafetyCheckRepository).save(captor.capture());
        assertThat(captor.getValue().getReason()).isEqualTo(DepositSafetyCheckReason.DEPOSIT_INFO_MISSING);
        verifyNoMarketLookup();
    }

    @Test
    @DisplayName("매매시세를 조회하지 못하면 ESTIMATED_PRICE_MISSING으로 판정불가 처리한다")
    void marketPriceUnavailableIsUnavailable() {
        setPolicy();
        Property property = property(10L, TransactionType.JEONSE, 200_000_000L);
        when(depositSafetyCheckRepository.findByPropertyId(10L)).thenReturn(Optional.empty());
        when(marketSaleDataClient.getSalePrice(10L)).thenReturn(Optional.empty());

        service.checkAndSave(property);

        var captor = org.mockito.ArgumentCaptor.forClass(DepositSafetyCheck.class);
        verify(depositSafetyCheckRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(DepositSafetyStatus.UNAVAILABLE);
        assertThat(captor.getValue().getReason()).isEqualTo(DepositSafetyCheckReason.ESTIMATED_PRICE_MISSING);
    }

    @Test
    @DisplayName("정상 계산되면 전세가율을 정수 퍼센트로 반올림해 저장한다")
    void calculatesRatioRoundedToWholePercent() {
        setPolicy();
        Property property = property(10L, TransactionType.JEONSE, 234_000_000L); // 234M / 300M = 78.0%
        when(depositSafetyCheckRepository.findByPropertyId(10L)).thenReturn(Optional.empty());
        when(marketSaleDataClient.getSalePrice(10L)).thenReturn(
                Optional.of(new MarketSalePrice(BigDecimal.valueOf(300_000_000L), LocalDate.of(2026, 7, 31))));

        service.checkAndSave(property);

        var captor = org.mockito.ArgumentCaptor.forClass(DepositSafetyCheck.class);
        verify(depositSafetyCheckRepository).save(captor.capture());
        DepositSafetyCheck saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo(DepositSafetyStatus.CALCULATED);
        assertThat(saved.getJeonseRatio()).isEqualByComparingTo(BigDecimal.valueOf(78));
        assertThat(saved.getReferenceDate()).isEqualTo(LocalDate.of(2026, 7, 31));
        assertThat(saved.getExplanation()).contains("78%");
    }

    @Test
    @DisplayName("전세가율이 80% 미만이면 안전 설명을, 80~100%면 주의 설명을 담는다")
    void explanationBandsCautionAndSafe() {
        setPolicy();
        assertRatioExplanation(150_000_000L, 300_000_000L, "안전"); // 50%
        assertRatioExplanation(270_000_000L, 300_000_000L, "주의"); // 90%
    }

    @Test
    @DisplayName("전세가율이 100~150%면 위험 설명을, 150% 초과면 입력값 재확인 안내를 담는다")
    void explanationBandsDangerAndAlert() {
        setPolicy();
        assertRatioExplanation(360_000_000L, 300_000_000L, "위험"); // 120%
        assertRatioExplanation(600_000_000L, 300_000_000L, "다시 확인"); // 200%
    }

    private void assertRatioExplanation(long deposit, long referencePrice, String expectedKeyword) {
        Property property = property(10L, TransactionType.JEONSE, deposit);
        when(depositSafetyCheckRepository.findByPropertyId(10L)).thenReturn(Optional.empty());
        when(marketSaleDataClient.getSalePrice(10L)).thenReturn(
                Optional.of(new MarketSalePrice(BigDecimal.valueOf(referencePrice), LocalDate.of(2026, 7, 31))));

        service.checkAndSave(property);

        var captor = org.mockito.ArgumentCaptor.forClass(DepositSafetyCheck.class);
        verify(depositSafetyCheckRepository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        assertThat(captor.getValue().getExplanation()).contains(expectedKeyword);
    }

    @Test
    @DisplayName("이미 저장된 결과가 있으면 새로 만들지 않고 덮어쓴다")
    void overwritesExistingCheck() {
        setPolicy();
        Property property = property(10L, TransactionType.JEONSE, 200_000_000L);
        DepositSafetyCheck existing = mock(DepositSafetyCheck.class);
        when(depositSafetyCheckRepository.findByPropertyId(10L)).thenReturn(Optional.of(existing));
        when(marketSaleDataClient.getSalePrice(10L)).thenReturn(
                Optional.of(new MarketSalePrice(BigDecimal.valueOf(300_000_000L), LocalDate.of(2026, 7, 31))));

        service.checkAndSave(property);

        verify(existing).overwrite(any(), any(), any(), any(), any(), any(), any(), any());
        verify(depositSafetyCheckRepository, org.mockito.Mockito.never()).save(any());
    }

    private void verifyNoMarketLookup() {
        org.mockito.Mockito.verifyNoInteractions(marketSaleDataClient);
    }
}
