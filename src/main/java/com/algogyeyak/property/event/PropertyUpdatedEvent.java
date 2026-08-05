package com.algogyeyak.property.event;

/**
 * 매물 정보가 수정됐을 때 발행되는 도메인 이벤트. property는 이 이벤트를 누가 구독하는지 몰라도 된다 -
 * risk-analysis가 이 이벤트를 구독해 위험 신호·전세가율을 재계산하지만, property 쪽 코드는 risk-analysis를
 * 전혀 import하지 않는다(도메인 결합 방지). 트랜잭션 커밋 후에만 처리해야 하는 부수 효과라
 * 리스너는 @TransactionalEventListener(phase = AFTER_COMMIT)로 구독해야 한다.
 */
public record PropertyUpdatedEvent(Long propertyId) {
}
