package com.algogyeyak.riskanalysis.service;

import com.algogyeyak.marketdata.service.MarketComparisonService;
import com.algogyeyak.property.entity.Property;
import com.algogyeyak.property.entity.PropertyType;
import com.algogyeyak.property.entity.TransactionType;
import com.algogyeyak.property.repository.PropertyRepository;
import com.algogyeyak.riskanalysis.client.MarketDataClient;
import com.algogyeyak.riskanalysis.dto.SignalCheckResult;
import com.algogyeyak.riskanalysis.enums.RiskSignalType;
import com.algogyeyak.riskanalysis.policy.RiskPolicyConfig;
import com.algogyeyak.riskanalysis.repository.PropertyRiskCheckRepository;
import com.algogyeyak.riskanalysis.repository.PropertyRiskRepository;
import com.algogyeyak.riskanalysis.signal.SignalDetector;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * REPEATABLE_READ 하에서 "바깥 트랜잭션의 첫 읽기로 스냅샷이 고정된 뒤, REQUIRES_NEW로 커밋된
 * 내용을 같은 바깥 트랜잭션의 이후 조회가 볼 수 있는지"를 H2로 직접 실험한다. H2 기본은
 * READ_COMMITTED라 이 실험에서는 isolation을 명시적으로 REPEATABLE_READ로 강제한다.
 *
 * SignalDetector는 실제 구현 대신 "무조건 리스크 발견"으로 고정한 mock 1개만 써서, checkAndSave()의
 * 반환값(신호 발견 개수)이 실제로 1이어야 하는 상황을 확실하게 만든다 - 실제 탐지기들은 이 테스트
 * 매물에 대해 아무 리스크도 못 찾을 수 있어(정상적으로 PropertyRiskCheck 행은 생기지만 "발견"은
 * 0건) 수정 여부를 구분하는 실험으로 부적합하다.
 */
@SpringBootTest
class RepeatableReadVisibilityExperimentTest {

    @Autowired
    private PropertyRepository propertyRepository;

    @Autowired
    private PropertyRiskCheckRepository riskCheckRepository;

    @Autowired
    private PropertyRiskRepository riskRepository;

    @Autowired
    private RiskPolicyConfig policyConfig;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @MockitoBean
    private MarketDataClient marketDataClient;

    // 실제 빈을 그대로 쓰면 checkAndSave()가 새로 호출하는 evictCache()가 진짜 Redis 연결을
    // 시도한다 - 이 테스트는 Testcontainers로 Redis를 띄우지 않으므로(REPEATABLE_READ 가시성만
    // 확인하는 실험이라 캐싱과 무관) mock으로 대체해 Redis 의존을 만들지 않는다.
    @MockitoBean
    private MarketComparisonService marketComparisonService;

    @MockitoBean
    private DepositSafetyCheckService depositSafetyCheckService;

    @Test
    void checkAndSaveReturnValueStaysCorrectEvenWhenRequeryCannotSeeItsOwnRequiresNewInsert() {
        when(marketDataClient.getComparison(org.mockito.ArgumentMatchers.anyLong())).thenReturn(Optional.empty());

        SignalDetector alwaysFindsRisk = mock(SignalDetector.class);
        when(alwaysFindsRisk.isEnabled()).thenReturn(true);
        when(alwaysFindsRisk.type()).thenReturn(RiskSignalType.PRICE_ANOMALY);
        when(alwaysFindsRisk.detect(any(), any())).thenReturn(SignalCheckResult.success("실험용 리스크 발견"));

        FakeListingSignalService serviceWithMockDetector = new FakeListingSignalService(
                List.of(alwaysFindsRisk), marketDataClient, marketComparisonService, riskCheckRepository, riskRepository,
                propertyRepository, depositSafetyCheckService, policyConfig, transactionManager);

        Property property = propertyRepository.saveAndFlush(Property.builder()
                .userId(1L)
                .title("REPEATABLE_READ 실험 매물")
                .propertyType(PropertyType.OFFICETEL)
                .transactionType(TransactionType.JEONSE)
                .deposit(100_000_000L)
                .area(30.0)
                .build());

        TransactionTemplate outerTx = new TransactionTemplate(transactionManager);
        outerTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        outerTx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);

        Integer[] holder = new Integer[2]; // [0]=재조회로 본 행 수(구버전 버그 재현용), [1]=checkAndSave() 반환값(수정 검증용)

        outerTx.executeWithoutResult(status -> {
            // 바깥 트랜잭션의 "첫 읽기" - checkAndSave(userId, propertyId)가 맨 처음
            // propertyRepository.findById()를 하는 것과 동일한 역할 (스냅샷 고정 지점 흉내)
            Property loaded = propertyRepository.findById(property.getId()).orElseThrow();

            // checkAndSave(Property) 실행 - 내부적으로 upsertCheck()/upsertRisk()가 REQUIRES_NEW로
            // insert하고 커밋한다 (실제 프로덕션 코드 경로 그대로 호출). 반환값은 이제 DB 재조회 없이
            // 이 호출 안에서 이미 알고 있는 판정 결과로 직접 센 개수다 - mock 탐지기가 무조건
            // 리스크를 찾으므로 정답은 1이어야 한다.
            int returnedCount = serviceWithMockDetector.checkAndSave(loaded);
            holder[1] = returnedCount;

            // (버그였던 구버전의 동작을 여전히 보여주기 위한 대조군) 바깥(같은) 트랜잭션에서 방금
            // REQUIRES_NEW로 커밋된 행을 다시 조회 - REPEATABLE_READ 하에서는 0이 나온다(구버전
            // checkAndSummarize()가 getSignals()로 재조회했다면 이 값을 signalCount로 잘못 반환했을 것).
            holder[0] = riskRepository.findAllByPropertyId(property.getId()).size();
        });

        // 별도의 새 트랜잭션(새 스냅샷)에서 조회 - 실제로 몇 건이 커밋됐는지 확인용 기준선.
        int actualCommittedCount = riskRepository.findAllByPropertyId(property.getId()).size();

        System.out.println("=== EXPERIMENT RESULT ===");
        System.out.println("(대조군) 바깥 트랜잭션 안에서 재조회 시 보인 PropertyRisk 행 수: " + holder[0] + " (REPEATABLE_READ 문제 재현 - 0이 나오면 버그 재현 성공)");
        System.out.println("(수정 검증) checkAndSave()가 직접 반환한 발견 개수: " + holder[1] + " (1이어야 정상 - DB 재조회 없이 정확해야 함)");
        System.out.println("새 트랜잭션에서 조회한 실제 커밋된 PropertyRisk 행 수: " + actualCommittedCount);
        System.out.println("=========================");

        assertThat(actualCommittedCount).isEqualTo(1);
        assertThat(holder[0]).as("REPEATABLE_READ 스냅샷 문제로 바깥 트랜잭션의 재조회는 커밋된 행을 못 봐야 한다(버그 재현)").isZero();
        assertThat(holder[1]).as("checkAndSave()의 반환값은 재조회에 의존하지 않으므로 항상 정확해야 한다(수정 검증)").isEqualTo(1);
    }
}
