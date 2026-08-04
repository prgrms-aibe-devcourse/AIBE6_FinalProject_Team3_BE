package com.algogyeyak.riskanalysis.service;

import com.algogyeyak.global.error.ErrorCode;
import com.algogyeyak.global.exception.BusinessException;
import com.algogyeyak.property.entity.Property;
import com.algogyeyak.property.entity.PropertyType;
import com.algogyeyak.property.entity.TransactionType;
import com.algogyeyak.property.repository.PropertyRepository;
import com.algogyeyak.riskanalysis.client.MarketDataClient;
import com.algogyeyak.riskanalysis.dto.RiskSignalListResponse;
import com.algogyeyak.riskanalysis.dto.RiskSignalResponse;
import com.algogyeyak.riskanalysis.entity.PropertyRisk;
import com.algogyeyak.riskanalysis.entity.PropertyRiskCheck;
import com.algogyeyak.riskanalysis.enums.RiskSignalType;
import com.algogyeyak.riskanalysis.policy.RiskPolicyConfig;
import com.algogyeyak.riskanalysis.dto.RiskAnalysisSummaryResponse;
import com.algogyeyak.riskanalysis.repository.PropertyRiskCheckRepository;
import com.algogyeyak.riskanalysis.repository.PropertyRiskRepository;
import com.algogyeyak.riskanalysis.signal.SignalDetector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("FakeListingSignalService")
class FakeListingSignalServiceTest {

    private final PropertyRepository propertyRepository = mock(PropertyRepository.class);
    private final PropertyRiskCheckRepository riskCheckRepository = mock(PropertyRiskCheckRepository.class);
    private final PropertyRiskRepository riskRepository = mock(PropertyRiskRepository.class);
    private final MarketDataClient marketDataClient = mock(MarketDataClient.class);
    private final RiskPolicyConfig policyConfig = new RiskPolicyConfig();
    private final FakeListingSignalService service = new FakeListingSignalService(
            List.of(mock(SignalDetector.class)), marketDataClient, riskCheckRepository, riskRepository,
            propertyRepository, policyConfig);

    private Property property(Long id, Long ownerId) {
        Property property = Property.builder()
                .userId(ownerId)
                .title("테스트 매물")
                .propertyType(PropertyType.OFFICETEL)
                .transactionType(TransactionType.JEONSE)
                .deposit(10_000_000L)
                .area(20.0)
                .build();
        ReflectionTestUtils.setField(property, "id", id);
        return property;
    }

    @Test
    @DisplayName("본인 매물의 신호 목록을 checkedAt 순서 그대로 조회한다")
    void getSignalsReturnsSignalsForOwnedProperty() {
        Property property = property(10L, 1L);
        when(propertyRepository.findById(10L)).thenReturn(Optional.of(property));

        PropertyRiskCheck check = PropertyRiskCheck.success(property, RiskSignalType.DUPLICATE_LISTING, "v1.0");
        PropertyRisk risk = PropertyRisk.of(property, RiskSignalType.DUPLICATE_LISTING, "동일 주소로 등록된 다른 매물이 있어요");
        when(riskCheckRepository.findAllByPropertyId(10L)).thenReturn(List.of(check));
        when(riskRepository.findAllByPropertyId(10L)).thenReturn(List.of(risk));

        RiskSignalListResponse result = service.getSignals(1L, 10L);

        assertThat(result.propertyId()).isEqualTo(10L);
        assertThat(result.signalCount()).isEqualTo(1);
        assertThat(result.signals()).hasSize(1);
        assertThat(result.signals().get(0).signalType()).isEqualTo(RiskSignalType.DUPLICATE_LISTING);
        assertThat(result.signals().get(0).description()).isEqualTo("동일 주소로 등록된 다른 매물이 있어요");
        assertThat(result.disclaimer()).isEqualTo(RiskSignalListResponse.DISCLAIMER);
    }

    @Test
    @DisplayName("존재하지 않는 매물이면 PROPERTY_NOT_FOUND 예외가 발생한다")
    void getSignalsThrowsWhenPropertyNotFound() {
        when(propertyRepository.findById(10L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getSignals(1L, 10L))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception ->
                        assertThat(((BusinessException) exception).getErrorCode()).isEqualTo(ErrorCode.PROPERTY_NOT_FOUND)
                );
    }

    @Test
    @DisplayName("본인 소유가 아닌 매물이면 PROPERTY_ACCESS_DENIED 예외가 발생한다")
    void getSignalsThrowsWhenNotOwner() {
        when(propertyRepository.findById(10L)).thenReturn(Optional.of(property(10L, 999L)));

        assertThatThrownBy(() -> service.getSignals(1L, 10L))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception ->
                        assertThat(((BusinessException) exception).getErrorCode()).isEqualTo(ErrorCode.PROPERTY_ACCESS_DENIED)
                );
    }

    @Test
    @DisplayName("삭제된 매물이면 PROPERTY_NOT_FOUND 예외가 발생한다")
    void getSignalsThrowsWhenPropertyDeleted() {
        Property deleted = property(10L, 1L);
        deleted.delete();
        when(propertyRepository.findById(10L)).thenReturn(Optional.of(deleted));

        assertThatThrownBy(() -> service.getSignals(1L, 10L))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception ->
                        assertThat(((BusinessException) exception).getErrorCode()).isEqualTo(ErrorCode.PROPERTY_NOT_FOUND)
                );
    }

    @Test
    @DisplayName("checkAndSave(userId, propertyId)는 본인 매물이면 예외 없이 신호 판정을 수행한다")
    void checkAndSaveWithOwnerCheckRunsWhenOwned() {
        when(propertyRepository.findById(10L)).thenReturn(Optional.of(property(10L, 1L)));

        service.checkAndSave(1L, 10L);

        // 예외 없이 끝나면 성공 - 오케스트레이션 자체(탐지기 실행)는 FakeListingSignalService의
        // 기존 checkAndSave(Property) 책임이라 여기서 다시 검증하지 않는다.
    }

    @Test
    @DisplayName("checkAndSave(userId, propertyId)는 존재하지 않는 매물이면 PROPERTY_NOT_FOUND 예외가 발생한다")
    void checkAndSaveWithOwnerCheckThrowsWhenPropertyNotFound() {
        when(propertyRepository.findById(10L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.checkAndSave(1L, 10L))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception ->
                        assertThat(((BusinessException) exception).getErrorCode()).isEqualTo(ErrorCode.PROPERTY_NOT_FOUND)
                );
    }

    @Test
    @DisplayName("checkAndSave(userId, propertyId)는 본인 소유가 아니면 PROPERTY_ACCESS_DENIED 예외가 발생한다")
    void checkAndSaveWithOwnerCheckThrowsWhenNotOwner() {
        when(propertyRepository.findById(10L)).thenReturn(Optional.of(property(10L, 999L)));

        assertThatThrownBy(() -> service.checkAndSave(1L, 10L))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception ->
                        assertThat(((BusinessException) exception).getErrorCode()).isEqualTo(ErrorCode.PROPERTY_ACCESS_DENIED)
                );
    }

    @Test
    @DisplayName("checkAndSave(userId, propertyId)는 삭제된 매물이면 PROPERTY_NOT_FOUND 예외가 발생한다")
    void checkAndSaveWithOwnerCheckThrowsWhenPropertyDeleted() {
        Property deleted = property(10L, 1L);
        deleted.delete();
        when(propertyRepository.findById(10L)).thenReturn(Optional.of(deleted));

        assertThatThrownBy(() -> service.checkAndSave(1L, 10L))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception ->
                        assertThat(((BusinessException) exception).getErrorCode()).isEqualTo(ErrorCode.PROPERTY_NOT_FOUND)
                );
    }

    @Test
    @DisplayName("checkAndSummarize()는 실행 후 리스크가 발견된 신호 개수와 정책 버전을 요약해 반환한다")
    void checkAndSummarizeReturnsSignalCountAndPolicyVersion() {
        ReflectionTestUtils.setField(policyConfig, "version", "v1.0");
        Property property = property(10L, 1L);
        when(propertyRepository.findById(10L)).thenReturn(Optional.of(property));

        PropertyRiskCheck foundCheck = PropertyRiskCheck.success(property, RiskSignalType.DUPLICATE_LISTING, "v1.0");
        PropertyRisk foundRisk = PropertyRisk.of(property, RiskSignalType.DUPLICATE_LISTING, "동일 주소로 등록된 다른 매물이 있어요");
        PropertyRiskCheck cleanCheck = PropertyRiskCheck.success(property, RiskSignalType.SHORT_TERM_RELISTING, "v1.0");
        when(riskCheckRepository.findAllByPropertyId(10L)).thenReturn(List.of(foundCheck, cleanCheck));
        when(riskRepository.findAllByPropertyId(10L)).thenReturn(List.of(foundRisk));

        RiskAnalysisSummaryResponse result = service.checkAndSummarize(1L, 10L);

        assertThat(result.propertyId()).isEqualTo(10L);
        assertThat(result.signalCount()).isEqualTo(1);
        assertThat(result.policyVersion()).isEqualTo("v1.0");
        assertThat(result.calculatedAt()).isNotNull();
    }

    @Test
    @DisplayName("checkAndSummarize()는 리스크가 발견된 신호가 없으면 signalCount 0을 반환한다")
    void checkAndSummarizeReturnsZeroWhenNoRiskFound() {
        ReflectionTestUtils.setField(policyConfig, "version", "v1.0");
        Property property = property(10L, 1L);
        when(propertyRepository.findById(10L)).thenReturn(Optional.of(property));

        PropertyRiskCheck cleanCheck = PropertyRiskCheck.success(property, RiskSignalType.DUPLICATE_LISTING, "v1.0");
        when(riskCheckRepository.findAllByPropertyId(10L)).thenReturn(List.of(cleanCheck));
        when(riskRepository.findAllByPropertyId(10L)).thenReturn(List.of());

        RiskAnalysisSummaryResponse result = service.checkAndSummarize(1L, 10L);

        assertThat(result.propertyId()).isEqualTo(10L);
        assertThat(result.signalCount()).isEqualTo(0);
    }
}
