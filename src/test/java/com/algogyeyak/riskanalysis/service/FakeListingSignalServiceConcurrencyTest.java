package com.algogyeyak.riskanalysis.service;

import com.algogyeyak.marketdata.service.MarketComparisonService;
import com.algogyeyak.property.entity.Property;
import com.algogyeyak.property.entity.PropertyType;
import com.algogyeyak.property.entity.TransactionType;
import com.algogyeyak.property.repository.PropertyRepository;
import com.algogyeyak.riskanalysis.client.MarketDataClient;
import com.algogyeyak.riskanalysis.entity.PropertyRiskCheck;
import com.algogyeyak.riskanalysis.repository.PropertyRiskCheckRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * FakeListingSignalServiceTest는 Mockito 목 저장소라 upsertCheck()의 "없으면 insert" 로직이
 * 실제 DB 유니크 제약과 동시성 아래서도 안전한지는 검증하지 못한다 - 같은 매물에 대한 checkAndSave()
 * 두 번이 (React 클라이언트가 컴포넌트 마운트 시 두 번 호출하는 경우, 또는 사용자 조회와
 * PropertyUpdatedEvent 배치 재계산이 겹치는 경우 등으로) 정말 동시에 들어오면, 둘 다 "기존 행 없음"을
 * 보고 동시에 insert를 시도해 property_risk_checks의 (property_id, signal_type) 유니크 제약을
 * 위반하는 DataIntegrityViolationException이 실제로 났었다. 이 테스트는 실제 H2 DB + 실제 리포지토리로
 * upsertCheck()/upsertRisk()가 insert를 REQUIRES_NEW 트랜잭션으로 격리해 이 경쟁을 실제로 막는지
 * 확인한다(외부 API 호출 중 잠금을 오래 들고 있는 PESSIMISTIC_WRITE 락 방식은 폐기됨).
 */
@SpringBootTest
class FakeListingSignalServiceConcurrencyTest {

    @Autowired
    private FakeListingSignalService fakeListingSignalService;

    @Autowired
    private PropertyRepository propertyRepository;

    @Autowired
    private PropertyRiskCheckRepository riskCheckRepository;

    // 실제 외부 시세 비교 API를 타지 않도록 대체 - 이 테스트의 관심사는 신호 판정 결과의 정확성이
    // 아니라 upsert의 동시성 안전성이므로, comparison이 없을 때 각 탐지기가 내리는 판정이 무엇이든
    // 상관없다.
    @MockitoBean
    private MarketDataClient marketDataClient;

    // checkAndSave(Property)가 PRICE_ANOMALY 판정 직전에 evictCache()를 직접 호출하므로, 실제 빈을
    // 그대로 두면 이 테스트가 진짜 Redis 연결을 필요로 하게 된다 - 이 테스트는 동시성 안전성만
    // 확인하므로(Testcontainers로 Redis를 띄우지 않음) mock으로 대체한다.
    @MockitoBean
    private MarketComparisonService marketComparisonService;

    // FakeListingSignalService.checkAndSave(Property)는 이 서비스도 내부에서 호출하는데, 그 안의
    // 실제 시세 조회(MarketSaleDataClient)까지 이 테스트가 준비할 필요는 없으므로 통째로 대체한다.
    @MockitoBean
    private DepositSafetyCheckService depositSafetyCheckService;

    @Test
    @DisplayName("같은 매물에 대한 checkAndSave() 두 번이 동시에 들어와도 유니크 제약 위반 없이 처리된다")
    @Timeout(15)
    void concurrentCheckAndSaveForSamePropertyDoesNotViolateUniqueConstraint() throws Exception {
        when(marketDataClient.getComparison(org.mockito.ArgumentMatchers.anyLong())).thenReturn(Optional.empty());

        Property property = propertyRepository.saveAndFlush(Property.builder()
                .userId(1L)
                .title("동시성 테스트 매물")
                .propertyType(PropertyType.OFFICETEL)
                .transactionType(TransactionType.JEONSE)
                .deposit(100_000_000L)
                .area(30.0)
                .build());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<?> a = executor.submit(() -> fakeListingSignalService.checkAndSave(property));
        Future<?> b = executor.submit(() -> fakeListingSignalService.checkAndSave(property));
        // get()이 예외를 던지지 않는 것 자체가 "유니크 제약 위반이 두 스레드 중 어느 쪽에서도 새어
        // 나오지 않았다"는 확인이다 - 잠금이 없던 원래 코드에서는 둘 중 하나가 여기서
        // DataIntegrityViolationException으로 실패했다.
        a.get(10, TimeUnit.SECONDS);
        b.get(10, TimeUnit.SECONDS);
        executor.shutdown();

        List<PropertyRiskCheck> checks = riskCheckRepository.findAllByPropertyId(property.getId());
        long distinctSignalTypes = checks.stream().map(PropertyRiskCheck::getSignalType).distinct().count();
        assertThat(checks).as("신호 타입당 정확히 1건만 남아야 한다(동시 insert로 인한 중복 없음)")
                .hasSize((int) distinctSignalTypes);
    }
}
