package com.algogyeyak.riskanalysis.service;

import com.algogyeyak.global.error.ErrorCode;
import com.algogyeyak.global.exception.BusinessException;
import com.algogyeyak.property.entity.Property;
import com.algogyeyak.property.entity.PropertyType;
import com.algogyeyak.property.entity.TransactionType;
import com.algogyeyak.property.repository.PropertyRepository;
import com.algogyeyak.riskanalysis.client.MarketSaleDataClient;
import com.algogyeyak.riskanalysis.dto.DepositSafetyCheckResponse;
import com.algogyeyak.riskanalysis.dto.MarketSalePrice;
import com.algogyeyak.riskanalysis.entity.DepositSafetyCheck;
import com.algogyeyak.riskanalysis.enums.DepositSafetyCheckReason;
import com.algogyeyak.riskanalysis.enums.DepositSafetyStatus;
import com.algogyeyak.riskanalysis.policy.RiskPolicyConfig;
import com.algogyeyak.riskanalysis.repository.DepositSafetyCheckRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("DepositSafetyCheckService")
class DepositSafetyCheckServiceTest {

    private final DepositSafetyCheckRepository depositSafetyCheckRepository = mock(DepositSafetyCheckRepository.class);
    private final MarketSaleDataClient marketSaleDataClient = mock(MarketSaleDataClient.class);
    private final PropertyRepository propertyRepository = mock(PropertyRepository.class);
    private final com.algogyeyak.checklist.repository.ChecklistItemRepository checklistItemRepository =
            mock(com.algogyeyak.checklist.repository.ChecklistItemRepository.class);
    private final RiskPolicyConfig policyConfig = new RiskPolicyConfig();
    private final DepositSafetyCheckService service = new DepositSafetyCheckService(
            depositSafetyCheckRepository, marketSaleDataClient, propertyRepository, checklistItemRepository,
            policyConfig, mock(PlatformTransactionManager.class));

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
    }

    @Test
    @DisplayName("월세 매물은 시세 조회 없이 TRANSACTION_TYPE_UNSUPPORTED로 판정불가 처리한다")
    void monthlyRentIsUnavailableWithoutMarketLookup() {
        setPolicy();
        Property property = property(10L, TransactionType.MONTHLY_RENT, 10_000_000L);
        when(depositSafetyCheckRepository.findByPropertyId(10L)).thenReturn(Optional.empty());

        service.checkAndSave(property);

        var captor = org.mockito.ArgumentCaptor.forClass(DepositSafetyCheck.class);
        verify(depositSafetyCheckRepository).saveAndFlush(captor.capture());
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
        verify(depositSafetyCheckRepository).saveAndFlush(captor.capture());
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
        verify(depositSafetyCheckRepository).saveAndFlush(captor.capture());
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
                Optional.of(new MarketSalePrice(BigDecimal.valueOf(300_000_000L), LocalDate.of(2026, 7, 31), 5, 300)));

        service.checkAndSave(property);

        var captor = org.mockito.ArgumentCaptor.forClass(DepositSafetyCheck.class);
        verify(depositSafetyCheckRepository).saveAndFlush(captor.capture());
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
                Optional.of(new MarketSalePrice(BigDecimal.valueOf(referencePrice), LocalDate.of(2026, 7, 31), 5, 300)));

        service.checkAndSave(property);

        var captor = org.mockito.ArgumentCaptor.forClass(DepositSafetyCheck.class);
        verify(depositSafetyCheckRepository, org.mockito.Mockito.atLeastOnce()).saveAndFlush(captor.capture());
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
                Optional.of(new MarketSalePrice(BigDecimal.valueOf(300_000_000L), LocalDate.of(2026, 7, 31), 5, 300)));

        service.checkAndSave(property);

        verify(existing).overwrite(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        // @Version 낙관적 락 충돌을 잡아내려면 트랜잭션 커밋 시점의 암묵적 flush에 맡기지 않고
        // 명시적으로 saveAndFlush를 호출해야 한다 - updateCalculatedAbsorbingConflict() 참고.
        verify(depositSafetyCheckRepository).saveAndFlush(existing);
    }

    @Test
    @DisplayName("DepositSafetyCheck 동시 insert로 유니크 제약을 위반하면 재조회해서 덮어쓰는 방식으로 복구한다")
    void checkAndSaveRecoversFromConcurrentInsertRace() {
        setPolicy();
        Property property = property(10L, TransactionType.MONTHLY_RENT, 10_000_000L);
        DepositSafetyCheck winner = mock(DepositSafetyCheck.class);

        // 첫 조회 시점엔 아직 아무도 없다고 나오지만(레이스), saveAndFlush 시도 시 다른 트랜잭션이
        // 먼저 커밋해서 유니크 제약(property_id) 위반이 난다 - 재조회하면 그 사이 커밋된 행이 보인다.
        when(depositSafetyCheckRepository.findByPropertyId(10L))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(winner));
        // 첫 saveAndFlush(신규 insert)만 유니크 제약 위반으로 실패하고, 복구 과정에서 재조회한
        // 기존 행을 저장하는 두 번째 saveAndFlush(update)는 정상 처리된다.
        when(depositSafetyCheckRepository.saveAndFlush(any()))
                .thenThrow(new DataIntegrityViolationException("unique constraint violation"))
                .thenReturn(winner);

        service.checkAndSave(property);

        verify(winner).overwrite(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(depositSafetyCheckRepository, times(2)).saveAndFlush(any());
    }

    @Test
    @DisplayName("이미 저장된 행을 덮어쓰다 낙관적 락 충돌(동시 갱신)이 나면 예외를 흡수하고 조용히 넘어간다")
    void checkAndSaveAbsorbsOptimisticLockConflictOnExistingRow() {
        setPolicy();
        Property property = property(10L, TransactionType.MONTHLY_RENT, 10_000_000L);
        DepositSafetyCheck existing = mock(DepositSafetyCheck.class);
        when(depositSafetyCheckRepository.findByPropertyId(10L)).thenReturn(Optional.of(existing));
        when(depositSafetyCheckRepository.saveAndFlush(existing))
                .thenThrow(new ObjectOptimisticLockingFailureException(DepositSafetyCheck.class, 10L));

        org.assertj.core.api.Assertions.assertThatCode(() -> service.checkAndSave(property))
                .doesNotThrowAnyException();

        verify(existing).overwrite(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(depositSafetyCheckRepository).saveAndFlush(existing);
    }

    private void verifyNoMarketLookup() {
        org.mockito.Mockito.verifyNoInteractions(marketSaleDataClient);
    }

    @Test
    @DisplayName("get()은 본인 매물의 저장된 보증금 안전성 결과를 조회한다")
    void getReturnsSavedCheckForOwnedProperty() {
        Property property = property(10L, TransactionType.JEONSE, 200_000_000L);
        when(propertyRepository.findById(10L)).thenReturn(Optional.of(property));
        DepositSafetyCheck check = DepositSafetyCheck.calculated(
                property, BigDecimal.valueOf(82), null, null, LocalDate.of(2026, 7, 31), "설명", 5, 300, "v1.0");
        when(depositSafetyCheckRepository.findByPropertyId(10L)).thenReturn(Optional.of(check));

        DepositSafetyCheckResponse response = service.get(1L, 10L);

        assertThat(response.propertyId()).isEqualTo(10L);
        assertThat(response.status()).isEqualTo(DepositSafetyStatus.CALCULATED);
        assertThat(response.jeonseRatio()).isEqualTo(82);
    }

    @Test
    @DisplayName("get()은 전세가율이 위험 구간이고 소유권 취득일이 최근이면 recentOwnershipChangeWarning을 true로 반환한다")
    void getReturnsRecentOwnershipChangeWarningWhenBothConditionsMet() {
        setPolicy();
        ReflectionTestUtils.setField(policyConfig, "ownershipRecentChangeMonths", 6);
        Property property = property(10L, TransactionType.JEONSE, 200_000_000L);
        when(propertyRepository.findById(10L)).thenReturn(Optional.of(property));
        DepositSafetyCheck check = DepositSafetyCheck.calculated(
                property, BigDecimal.valueOf(120), null, null, LocalDate.of(2026, 7, 31), "설명", 5, 300, "v1.0");
        when(depositSafetyCheckRepository.findByPropertyId(10L)).thenReturn(Optional.of(check));

        com.algogyeyak.checklist.entity.ChecklistItem ownershipItem = mock(com.algogyeyak.checklist.entity.ChecklistItem.class);
        when(ownershipItem.getValue()).thenReturn(LocalDate.now().minusMonths(3).toString());
        when(checklistItemRepository.findByChecklist_Property_IdAndCode(
                10L, com.algogyeyak.checklist.entity.ChecklistItemCode.OWNERSHIP_ACQUISITION_DATE))
                .thenReturn(Optional.of(ownershipItem));

        DepositSafetyCheckResponse response = service.get(1L, 10L);

        assertThat(response.recentOwnershipChangeWarning()).isTrue();
    }

    @Test
    @DisplayName("get()은 전세가율이 위험 구간이어도 소유권 취득일이 오래됐으면 recentOwnershipChangeWarning을 false로 반환한다")
    void getReturnsNoWarningWhenOwnershipIsOld() {
        setPolicy();
        ReflectionTestUtils.setField(policyConfig, "ownershipRecentChangeMonths", 6);
        Property property = property(10L, TransactionType.JEONSE, 200_000_000L);
        when(propertyRepository.findById(10L)).thenReturn(Optional.of(property));
        DepositSafetyCheck check = DepositSafetyCheck.calculated(
                property, BigDecimal.valueOf(120), null, null, LocalDate.of(2026, 7, 31), "설명", 5, 300, "v1.0");
        when(depositSafetyCheckRepository.findByPropertyId(10L)).thenReturn(Optional.of(check));

        com.algogyeyak.checklist.entity.ChecklistItem ownershipItem = mock(com.algogyeyak.checklist.entity.ChecklistItem.class);
        when(ownershipItem.getValue()).thenReturn(LocalDate.now().minusMonths(24).toString());
        when(checklistItemRepository.findByChecklist_Property_IdAndCode(
                10L, com.algogyeyak.checklist.entity.ChecklistItemCode.OWNERSHIP_ACQUISITION_DATE))
                .thenReturn(Optional.of(ownershipItem));

        DepositSafetyCheckResponse response = service.get(1L, 10L);

        assertThat(response.recentOwnershipChangeWarning()).isFalse();
    }

    @Test
    @DisplayName("get()은 아직 체크 결과가 없으면 status가 null인 응답을 반환한다")
    void getReturnsNullStatusWhenNeverChecked() {
        Property property = property(10L, TransactionType.JEONSE, 200_000_000L);
        when(propertyRepository.findById(10L)).thenReturn(Optional.of(property));
        when(depositSafetyCheckRepository.findByPropertyId(10L)).thenReturn(Optional.empty());

        DepositSafetyCheckResponse response = service.get(1L, 10L);

        assertThat(response.status()).isNull();
    }

    @Test
    @DisplayName("get()은 존재하지 않는 매물이면 PROPERTY_NOT_FOUND 예외가 발생한다")
    void getThrowsWhenPropertyNotFound() {
        when(propertyRepository.findById(10L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(1L, 10L))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception ->
                        assertThat(((BusinessException) exception).getErrorCode()).isEqualTo(ErrorCode.PROPERTY_NOT_FOUND)
                );
    }

    @Test
    @DisplayName("get()은 본인 소유가 아니면 PROPERTY_ACCESS_DENIED 예외가 발생한다")
    void getThrowsWhenNotOwner() {
        Property property = Property.builder()
                .userId(999L)
                .propertyType(PropertyType.OFFICETEL)
                .transactionType(TransactionType.JEONSE)
                .deposit(200_000_000L)
                .area(20.0)
                .build();
        ReflectionTestUtils.setField(property, "id", 10L);
        when(propertyRepository.findById(10L)).thenReturn(Optional.of(property));

        assertThatThrownBy(() -> service.get(1L, 10L))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception ->
                        assertThat(((BusinessException) exception).getErrorCode()).isEqualTo(ErrorCode.PROPERTY_ACCESS_DENIED)
                );
    }

    @Test
    @DisplayName("get()은 삭제된 매물이면 PROPERTY_NOT_FOUND 예외가 발생한다")
    void getThrowsWhenPropertyDeleted() {
        Property property = property(10L, TransactionType.JEONSE, 200_000_000L);
        property.delete();
        when(propertyRepository.findById(10L)).thenReturn(Optional.of(property));

        assertThatThrownBy(() -> service.get(1L, 10L))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception ->
                        assertThat(((BusinessException) exception).getErrorCode()).isEqualTo(ErrorCode.PROPERTY_NOT_FOUND)
                );
    }

    @Test
    @DisplayName("recalculate()는 선순위보증금과 근저당 채권최고액을 분자에 합산해 재계산한다")
    void recalculateAppliesSeniorDepositAndMaxClaimAmount() {
        setPolicy();
        // (200M deposit + 50M senior + 10M maxClaim) / 300M = 86.666...% -> 87%
        Property property = property(10L, TransactionType.JEONSE, 200_000_000L);
        when(propertyRepository.findById(10L)).thenReturn(Optional.of(property));
        when(depositSafetyCheckRepository.findByPropertyId(10L)).thenReturn(Optional.empty());
        when(depositSafetyCheckRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(marketSaleDataClient.getSalePrice(10L)).thenReturn(
                Optional.of(new MarketSalePrice(BigDecimal.valueOf(300_000_000L), LocalDate.of(2026, 7, 31), 5, 300)));

        DepositSafetyCheckResponse response = service.recalculate(1L, 10L, 50_000_000L, 10_000_000L);

        assertThat(response.jeonseRatio()).isEqualTo(87);
        assertThat(response.seniorDepositApplied()).isTrue();
        assertThat(response.seniorDeposit()).isEqualTo(50_000_000L);
        assertThat(response.maxClaimAmount()).isEqualTo(10_000_000L);
    }

    @Test
    @DisplayName("recalculate()는 근저당 채권최고액 없이 선순위보증금만으로도 계산한다")
    void recalculateWorksWithSeniorDepositOnly() {
        setPolicy();
        Property property = property(10L, TransactionType.JEONSE, 200_000_000L);
        when(propertyRepository.findById(10L)).thenReturn(Optional.of(property));
        when(depositSafetyCheckRepository.findByPropertyId(10L)).thenReturn(Optional.empty());
        when(depositSafetyCheckRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(marketSaleDataClient.getSalePrice(10L)).thenReturn(
                Optional.of(new MarketSalePrice(BigDecimal.valueOf(300_000_000L), LocalDate.of(2026, 7, 31), 5, 300)));

        DepositSafetyCheckResponse response = service.recalculate(1L, 10L, 50_000_000L, null);

        // (200M + 50M) / 300M = 83.33% -> 83%
        assertThat(response.jeonseRatio()).isEqualTo(83);
        assertThat(response.maxClaimAmount()).isNull();
    }

    @Test
    @DisplayName("recalculate()는 존재하지 않는 매물이면 PROPERTY_NOT_FOUND 예외가 발생한다")
    void recalculateThrowsWhenPropertyNotFound() {
        when(propertyRepository.findById(10L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.recalculate(1L, 10L, 50_000_000L, null))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception ->
                        assertThat(((BusinessException) exception).getErrorCode()).isEqualTo(ErrorCode.PROPERTY_NOT_FOUND)
                );
    }

    @Test
    @DisplayName("recalculate()는 본인 소유가 아니면 PROPERTY_ACCESS_DENIED 예외가 발생한다")
    void recalculateThrowsWhenNotOwner() {
        Property property = Property.builder()
                .userId(999L)
                .propertyType(PropertyType.OFFICETEL)
                .transactionType(TransactionType.JEONSE)
                .deposit(200_000_000L)
                .area(20.0)
                .build();
        ReflectionTestUtils.setField(property, "id", 10L);
        when(propertyRepository.findById(10L)).thenReturn(Optional.of(property));

        assertThatThrownBy(() -> service.recalculate(1L, 10L, 50_000_000L, null))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception ->
                        assertThat(((BusinessException) exception).getErrorCode()).isEqualTo(ErrorCode.PROPERTY_ACCESS_DENIED)
                );
    }
}
