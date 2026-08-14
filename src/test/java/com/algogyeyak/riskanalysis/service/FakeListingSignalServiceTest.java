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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("FakeListingSignalService")
class FakeListingSignalServiceTest {

    private final PropertyRepository propertyRepository = mock(PropertyRepository.class);
    private final PropertyRiskCheckRepository riskCheckRepository = mock(PropertyRiskCheckRepository.class);
    private final PropertyRiskRepository riskRepository = mock(PropertyRiskRepository.class);
    private final MarketDataClient marketDataClient = mock(MarketDataClient.class);
    private final DepositSafetyCheckService depositSafetyCheckService = mock(DepositSafetyCheckService.class);
    private final RiskPolicyConfig policyConfig = new RiskPolicyConfig();
    private final FakeListingSignalService service = new FakeListingSignalService(
            List.of(mock(SignalDetector.class)), marketDataClient, riskCheckRepository, riskRepository,
            propertyRepository, depositSafetyCheckService, policyConfig, mock(PlatformTransactionManager.class));

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
    @DisplayName("checkAndSave(Property)는 신호 탐지와 함께 보증금 안전성 체크도 실행한다")
    void checkAndSaveAlsoRunsDepositSafetyCheck() {
        Property property = property(10L, 1L);
        when(propertyRepository.findById(10L)).thenReturn(Optional.of(property));

        service.checkAndSave(1L, 10L);

        verify(depositSafetyCheckService).checkAndSave(property);
    }

    @Test
    @DisplayName("보증금 안전성 체크가 예외를 던져도 이미 판정된 신호 개수는 그대로 반환하고 예외를 전파하지 않는다")
    void checkAndSaveAbsorbsDepositSafetyCheckFailure() {
        Property property = property(10L, 1L);
        org.mockito.Mockito.doThrow(new RuntimeException("지오코딩 클라이언트 실패"))
                .when(depositSafetyCheckService).checkAndSave(property);

        int signalCount = service.checkAndSave(property);

        assertThat(signalCount).isEqualTo(0);
    }

    @Test
    @DisplayName("PropertyRiskCheck 동시 insert로 유니크 제약을 위반하면 재조회해서 덮어쓰는 방식으로 복구한다")
    void upsertCheckRecoversFromConcurrentInsertRace() {
        Property property = property(10L, 1L);
        com.algogyeyak.riskanalysis.signal.SignalDetector detector = mock(com.algogyeyak.riskanalysis.signal.SignalDetector.class);
        when(detector.isEnabled()).thenReturn(true);
        when(detector.type()).thenReturn(RiskSignalType.PRICE_ANOMALY);
        when(detector.detect(any(), any())).thenReturn(
                com.algogyeyak.riskanalysis.dto.SignalCheckResult.undeterminable(com.algogyeyak.riskanalysis.enums.RiskCheckReason.NO_COMPARABLE_TRANSACTION));

        FakeListingSignalService serviceWithDetector = new FakeListingSignalService(
                List.of(detector), marketDataClient, riskCheckRepository, riskRepository,
                propertyRepository, depositSafetyCheckService, policyConfig, mock(PlatformTransactionManager.class));

        // 첫 조회 시점엔 아직 아무도 없다고 나오지만(레이스), saveAndFlush 시도 시 다른 트랜잭션이
        // 먼저 커밋해서 유니크 제약 위반이 난다 - 재조회하면 그 사이 커밋된 행이 보인다.
        when(riskCheckRepository.findByPropertyIdAndSignalType(10L, RiskSignalType.PRICE_ANOMALY))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(PropertyRiskCheck.success(property, RiskSignalType.PRICE_ANOMALY, "v1.0")));
        // 첫 saveAndFlush(신규 insert)만 유니크 제약 위반으로 실패하고, 복구 과정에서 재조회한
        // 기존 행을 저장하는 두 번째 saveAndFlush(update)는 정상 처리된다 - 실제로도 update는
        // insert와 달리 이 유니크 제약에 걸릴 이유가 없다.
        when(riskCheckRepository.saveAndFlush(any(PropertyRiskCheck.class)))
                .thenThrow(new DataIntegrityViolationException("unique constraint violation"))
                .thenReturn(null);

        serviceWithDetector.checkAndSave(property);

        // 예외가 밖으로 안 새어 나오면 성공 - 재조회한 기존 행에 overwrite()로 복구됐다는 뜻.
        verify(riskCheckRepository, times(2)).saveAndFlush(any(PropertyRiskCheck.class));
    }

    @Test
    @DisplayName("PropertyRisk 동시 insert로 유니크 제약을 위반하면 재조회해서 덮어쓰는 방식으로 복구한다")
    void upsertRiskRecoversFromConcurrentInsertRace() {
        Property property = property(10L, 1L);
        com.algogyeyak.riskanalysis.signal.SignalDetector detector = mock(com.algogyeyak.riskanalysis.signal.SignalDetector.class);
        when(detector.isEnabled()).thenReturn(true);
        when(detector.type()).thenReturn(RiskSignalType.DUPLICATE_LISTING);
        when(detector.detect(any(), any())).thenReturn(
                com.algogyeyak.riskanalysis.dto.SignalCheckResult.success("동일 주소로 등록된 다른 매물이 있어요"));

        FakeListingSignalService serviceWithDetector = new FakeListingSignalService(
                List.of(detector), marketDataClient, riskCheckRepository, riskRepository,
                propertyRepository, depositSafetyCheckService, policyConfig, mock(PlatformTransactionManager.class));

        when(riskCheckRepository.findByPropertyIdAndSignalType(10L, RiskSignalType.DUPLICATE_LISTING))
                .thenReturn(Optional.empty());
        when(riskRepository.findByPropertyIdAndSignalType(10L, RiskSignalType.DUPLICATE_LISTING))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(PropertyRisk.of(property, RiskSignalType.DUPLICATE_LISTING, "동일 주소로 등록된 다른 매물이 있어요")));
        when(riskRepository.saveAndFlush(any(PropertyRisk.class)))
                .thenThrow(new DataIntegrityViolationException("unique constraint violation"))
                .thenReturn(null);

        serviceWithDetector.checkAndSave(property);

        verify(riskRepository, times(2)).saveAndFlush(any(PropertyRisk.class));
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

    // checkAndSummarize()는 더 이상 저장 직후 riskCheckRepository/riskRepository를 재조회해서
    // signalCount를 만들지 않는다(REQUIRES_NEW로 커밋된 신규 행이 MySQL REPEATABLE READ 하에서
    // 같은 트랜잭션의 재조회에 안 보일 수 있는 문제 때문 - FakeListingSignalService.checkAndSummarize()
    // 주석 참고). 대신 checkAndSave()가 감지기 판정 결과를 그 자리에서 직접 세어 반환한 값을 그대로
    // 쓰므로, 아래 두 테스트는 repository mock이 아니라 감지기(SignalDetector) mock으로 시나리오를
    // 구성한다.
    @Test
    @DisplayName("checkAndSummarize()는 실행 후 리스크가 발견된 신호 개수와 정책 버전을 요약해 반환한다")
    void checkAndSummarizeReturnsSignalCountAndPolicyVersion() {
        ReflectionTestUtils.setField(policyConfig, "version", "v1.0");
        Property property = property(10L, 1L);
        when(propertyRepository.findById(10L)).thenReturn(Optional.of(property));

        SignalDetector foundDetector = mock(SignalDetector.class);
        when(foundDetector.isEnabled()).thenReturn(true);
        when(foundDetector.type()).thenReturn(RiskSignalType.DUPLICATE_LISTING);
        when(foundDetector.detect(any(), any())).thenReturn(
                com.algogyeyak.riskanalysis.dto.SignalCheckResult.success("동일 주소로 등록된 다른 매물이 있어요"));
        SignalDetector cleanDetector = mock(SignalDetector.class);
        when(cleanDetector.isEnabled()).thenReturn(true);
        when(cleanDetector.type()).thenReturn(RiskSignalType.SHORT_TERM_RELISTING);
        when(cleanDetector.detect(any(), any())).thenReturn(
                com.algogyeyak.riskanalysis.dto.SignalCheckResult.success(null));

        FakeListingSignalService serviceWithDetectors = new FakeListingSignalService(
                List.of(foundDetector, cleanDetector), marketDataClient, riskCheckRepository, riskRepository,
                propertyRepository, depositSafetyCheckService, policyConfig, mock(PlatformTransactionManager.class));

        RiskAnalysisSummaryResponse result = serviceWithDetectors.checkAndSummarize(1L, 10L);

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

        SignalDetector cleanDetector = mock(SignalDetector.class);
        when(cleanDetector.isEnabled()).thenReturn(true);
        when(cleanDetector.type()).thenReturn(RiskSignalType.DUPLICATE_LISTING);
        when(cleanDetector.detect(any(), any())).thenReturn(
                com.algogyeyak.riskanalysis.dto.SignalCheckResult.success(null));

        FakeListingSignalService serviceWithDetector = new FakeListingSignalService(
                List.of(cleanDetector), marketDataClient, riskCheckRepository, riskRepository,
                propertyRepository, depositSafetyCheckService, policyConfig, mock(PlatformTransactionManager.class));

        RiskAnalysisSummaryResponse result = serviceWithDetector.checkAndSummarize(1L, 10L);

        assertThat(result.propertyId()).isEqualTo(10L);
        assertThat(result.signalCount()).isEqualTo(0);
    }
}
