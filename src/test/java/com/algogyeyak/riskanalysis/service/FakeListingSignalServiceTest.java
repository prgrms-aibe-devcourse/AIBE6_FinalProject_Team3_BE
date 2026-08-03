package com.algogyeyak.riskanalysis.service;

import com.algogyeyak.global.error.ErrorCode;
import com.algogyeyak.global.exception.BusinessException;
import com.algogyeyak.property.entity.Property;
import com.algogyeyak.property.entity.PropertyType;
import com.algogyeyak.property.entity.TransactionType;
import com.algogyeyak.property.repository.PropertyRepository;
import com.algogyeyak.riskanalysis.client.MarketDataClient;
import com.algogyeyak.riskanalysis.dto.RiskSignalResponse;
import com.algogyeyak.riskanalysis.entity.PropertyRisk;
import com.algogyeyak.riskanalysis.entity.PropertyRiskCheck;
import com.algogyeyak.riskanalysis.enums.RiskSignalType;
import com.algogyeyak.riskanalysis.policy.RiskPolicyConfig;
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

        List<RiskSignalResponse> result = service.getSignals(1L, 10L);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).signalType()).isEqualTo(RiskSignalType.DUPLICATE_LISTING);
        assertThat(result.get(0).description()).isEqualTo("동일 주소로 등록된 다른 매물이 있어요");
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

    // checkAndSave(userId, propertyId) 오버로드는 다음 단계에서 별도로 TDD 진행 예정.
}
