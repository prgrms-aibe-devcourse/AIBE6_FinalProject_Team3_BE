package com.algogyeyak.riskanalysis.service;

import com.algogyeyak.property.entity.Property;
import com.algogyeyak.property.event.PropertyUpdatedEvent;
import com.algogyeyak.property.repository.PropertyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 매물 수정 시 위험 신호·전세가율을 자동으로 재계산한다. property 도메인이 직접 이 서비스를
 * 호출하지 않고 {@link PropertyUpdatedEvent}를 발행하면 이 리스너가 구독하는 방식이라, property는
 * risk-analysis를 전혀 몰라도 된다(도메인 결합 방지).
 *
 * AFTER_COMMIT에서만 처리하는 이유: 매물 수정 트랜잭션이 커밋되기 전에 재계산이 실패하면 그
 * 예외가 같은 트랜잭션(REQUIRED propagation)을 rollback-only로 표시해버려, 예외를 여기서 잡아도
 * 매물 수정 자체가 롤백돼버린다("위험도 계산 실패가 매물 상세 조회 전체 실패로 이어지지 않음"
 * 요구사항 위반). 커밋 후 별도 트랜잭션으로 처리하면 재계산이 실패해도 매물 수정엔 영향이 없다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RiskRecalculationService {

    private final PropertyRepository propertyRepository;
    private final FakeListingSignalService fakeListingSignalService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPropertyUpdated(PropertyUpdatedEvent event) {
        try {
            propertyRepository.findById(event.propertyId())
                    .ifPresent(fakeListingSignalService::checkAndSave);
        } catch (RuntimeException e) {
            log.error("매물 수정에 따른 위험 신호 재계산 실패 - propertyId: {}, error: {}",
                    event.propertyId(), e.getMessage());
        }
    }
}
