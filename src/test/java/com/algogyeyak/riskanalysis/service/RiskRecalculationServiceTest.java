package com.algogyeyak.riskanalysis.service;

import com.algogyeyak.property.entity.Property;
import com.algogyeyak.property.entity.PropertyType;
import com.algogyeyak.property.entity.TransactionType;
import com.algogyeyak.property.event.PropertyUpdatedEvent;
import com.algogyeyak.property.repository.PropertyRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("RiskRecalculationService")
class RiskRecalculationServiceTest {

    private final PropertyRepository propertyRepository = mock(PropertyRepository.class);
    private final FakeListingSignalService fakeListingSignalService = mock(FakeListingSignalService.class);
    private final RiskRecalculationService service =
            new RiskRecalculationService(propertyRepository, fakeListingSignalService);

    private Property property(Long id) {
        Property property = Property.builder()
                .userId(1L)
                .propertyType(PropertyType.OFFICETEL)
                .transactionType(TransactionType.JEONSE)
                .deposit(200_000_000L)
                .area(20.0)
                .build();
        ReflectionTestUtils.setField(property, "id", id);
        return property;
    }

    @Test
    @DisplayName("매물 수정 이벤트를 받으면 해당 매물의 위험 신호·전세가율을 재계산한다")
    void onPropertyUpdatedTriggersRecalculation() {
        Property property = property(10L);
        when(propertyRepository.findById(10L)).thenReturn(Optional.of(property));

        service.onPropertyUpdated(new PropertyUpdatedEvent(10L));

        verify(fakeListingSignalService).checkAndSave(property);
    }

    @Test
    @DisplayName("이벤트의 매물이 존재하지 않으면(삭제 등) 조용히 넘어간다")
    void onPropertyUpdatedSkipsWhenPropertyNotFound() {
        when(propertyRepository.findById(10L)).thenReturn(Optional.empty());

        service.onPropertyUpdated(new PropertyUpdatedEvent(10L));

        verifyNoInteractions(fakeListingSignalService);
    }

    @Test
    @DisplayName("재계산 중 예외가 발생해도 밖으로 던지지 않는다 - 매물 수정 자체를 실패시키면 안 되므로")
    void onPropertyUpdatedSwallowsExceptions() {
        Property property = property(10L);
        when(propertyRepository.findById(10L)).thenReturn(Optional.of(property));
        org.mockito.Mockito.doThrow(new RuntimeException("market-data 호출 실패"))
                .when(fakeListingSignalService).checkAndSave(property);

        service.onPropertyUpdated(new PropertyUpdatedEvent(10L));

        // 예외 없이 여기까지 도달하면 성공
    }
}
