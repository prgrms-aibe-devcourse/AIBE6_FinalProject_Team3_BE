package com.algogyeyak.riskanalysis.service;

import com.algogyeyak.property.entity.Property;
import com.algogyeyak.property.event.PropertyUpdatedEvent;
import com.algogyeyak.property.repository.PropertyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
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
 *
 * {@code @Async}인 이유: 이 메서드가 동기로 실행되면 {@code fakeListingSignalService.checkAndSave()}가
 * 국토부 실거래가 API를 최대 6~12회 순차 호출하는데, AFTER_COMMIT이라도 원래 요청을 처리하던 서블릿
 * 스레드가 그대로 이걸 기다리므로 매물 등록/수정 API 응답이 그만큼 지연된다(전수조사 성능 감사 결과,
 * 2026-08-24, RiskAnalysisAsyncConfig 참고). 비동기로 넘기면 등록/수정 API는 재계산을 기다리지 않고
 * 즉시 응답하고, 재계산 결과는 이후 상세/목록 조회 시 반영된다(원래도 이벤트 기반이라 즉시 반영을
 * 보장하지 않는 구조 - PropertyDetailResponse.checkSignalCount 등이 등록 직후엔 null일 수 있다는
 * 전제가 이미 있었음).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RiskRecalculationService {

    private final PropertyRepository propertyRepository;
    private final FakeListingSignalService fakeListingSignalService;

    @Async("riskRecalculationTaskExecutor")
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
