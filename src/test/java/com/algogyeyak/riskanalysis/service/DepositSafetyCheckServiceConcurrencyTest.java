package com.algogyeyak.riskanalysis.service;

import com.algogyeyak.property.entity.Property;
import com.algogyeyak.property.entity.PropertyType;
import com.algogyeyak.property.entity.TransactionType;
import com.algogyeyak.property.repository.PropertyRepository;
import com.algogyeyak.riskanalysis.client.MarketSaleDataClient;
import com.algogyeyak.riskanalysis.repository.DepositSafetyCheckRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DepositSafetyCheckServiceTest는 Mockito 목 저장소라 upsertUnavailable()/upsertCalculated()의
 * "없으면 insert" 로직이 실제 DB 유니크 제약과 동시성 아래서도 안전한지는 검증하지 못한다 -
 * FakeListingSignalServiceConcurrencyTest와 동일한 이유로, 같은 매물에 대한 checkAndSave() 두 번이
 * 정말 동시에 들어오면(POST /risk-analysis와 POST /deposit-safety/recalculate가 겹치는 경우 등)
 * 둘 다 "기존 행 없음"을 보고 동시에 insert를 시도해 property_deposit_safety_checks의 property_id
 * 유니크 제약을 위반할 수 있다. 이 테스트는 실제 H2 DB + 실제 리포지토리로 upsertUnavailable()이
 * insert를 REQUIRES_NEW 트랜잭션으로 격리해 이 경쟁을 실제로 막는지 확인한다.
 */
@SpringBootTest
class DepositSafetyCheckServiceConcurrencyTest {

    @Autowired
    private DepositSafetyCheckService depositSafetyCheckService;

    @Autowired
    private PropertyRepository propertyRepository;

    @Autowired
    private DepositSafetyCheckRepository depositSafetyCheckRepository;

    // 실제 외부 매매시세 API를 타지 않도록 대체 - 아래 테스트는 MONTHLY_RENT 매물을 써서 calculate()가
    // marketSaleDataClient를 아예 호출하지 않는 upsertUnavailable() 경로로 곧장 빠지지만,
    // FakeListingSignalServiceConcurrencyTest와 동일하게 관례적으로 명시 mock 처리한다.
    @MockitoBean
    private MarketSaleDataClient marketSaleDataClient;

    @Test
    @DisplayName("같은 매물에 대한 checkAndSave() 두 번이 동시에 들어와도 유니크 제약 위반 없이 처리된다")
    @Timeout(15)
    void concurrentCheckAndSaveForSamePropertyDoesNotViolateUniqueConstraint() throws Exception {
        // MONTHLY_RENT는 calculate()가 시세 조회 없이 곧장 upsertUnavailable()로 분기한다
        // (DepositSafetyCheckService.calculate() 참고) - 이 테스트의 관심사는 판정 결과가 아니라
        // upsert의 동시성 안전성이므로 가장 단순한 경로로 REQUIRES_NEW insert 경쟁만 재현한다.
        Property property = propertyRepository.saveAndFlush(Property.builder()
                .userId(1L)
                .title("동시성 테스트 매물")
                .propertyType(PropertyType.OFFICETEL)
                .transactionType(TransactionType.MONTHLY_RENT)
                .deposit(50_000_000L)
                .monthlyRent(500_000L)
                .area(30.0)
                .build());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<?> a = executor.submit(() -> depositSafetyCheckService.checkAndSave(property));
        Future<?> b = executor.submit(() -> depositSafetyCheckService.checkAndSave(property));
        // get()이 예외를 던지지 않는 것 자체가 "유니크 제약 위반이 두 스레드 중 어느 쪽에서도 새어
        // 나오지 않았다"는 확인이다 - 보호가 없었다면 둘 중 하나가 여기서 DataIntegrityViolationException으로
        // 실패했다.
        a.get(10, TimeUnit.SECONDS);
        b.get(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(depositSafetyCheckRepository.findByPropertyId(property.getId()))
                .as("경쟁 후에도 매물당 정확히 1건만 남아야 한다(동시 insert로 인한 중복 없음)")
                .isPresent();
    }
}
