package com.algogyeyak.riskanalysis.signal;

import com.algogyeyak.property.entity.Property;
import com.algogyeyak.property.entity.PropertyType;
import com.algogyeyak.property.entity.TransactionType;
import com.algogyeyak.riskanalysis.dto.MarketComparison;
import com.algogyeyak.riskanalysis.dto.SignalCheckResult;
import com.algogyeyak.riskanalysis.enums.MarketComparisonStatus;
import com.algogyeyak.riskanalysis.enums.MarketUnavailableReason;
import com.algogyeyak.riskanalysis.enums.RiskCheckReason;
import com.algogyeyak.riskanalysis.enums.RiskCheckStatus;
import com.algogyeyak.riskanalysis.enums.RiskSignalType;
import com.algogyeyak.riskanalysis.policy.RiskPolicyConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PriceAnomalyDetector")
class PriceAnomalyDetectorTest {

    private final RiskPolicyConfig policyConfig = new RiskPolicyConfig();
    private final PriceAnomalyDetector detector = new PriceAnomalyDetector(policyConfig);

    private Property property() {
        return Property.builder()
                .userId(1L)
                .title("테스트 매물")
                .propertyType(PropertyType.OFFICETEL)
                .transactionType(TransactionType.JEONSE)
                .deposit(180_000_000L)
                .area(20.0)
                .build();
    }

    private MarketComparison success(double differenceRate) {
        return new MarketComparison(10L, BigDecimal.valueOf(200_000_000L), BigDecimal.valueOf(180_000_000L),
                BigDecimal.valueOf(differenceRate), 3, null, 300, null, MarketComparisonStatus.SUCCESS);
    }

    private MarketComparison undeterminable(MarketUnavailableReason reason) {
        return new MarketComparison(10L, null, null, null, 0, null, null, reason, MarketComparisonStatus.UNDETERMINABLE);
    }

    private MarketComparison failed(MarketUnavailableReason reason) {
        return new MarketComparison(10L, null, null, null, 0, null, null, reason, MarketComparisonStatus.FAILED);
    }

    @Test
    @DisplayName("type()은 PRICE_ANOMALY를 반환한다")
    void typeReturnsPriceAnomaly() {
        assertThat(detector.type()).isEqualTo(RiskSignalType.PRICE_ANOMALY);
    }

    @Test
    @DisplayName("isEnabled()는 true를 반환한다")
    void isEnabledReturnsTrue() {
        assertThat(detector.isEnabled()).isTrue();
    }

    @Test
    @DisplayName("comparison이 없으면(null) 판정 불가(NO_COMPARABLE_TRANSACTION)를 반환한다")
    void detectReturnsUndeterminableWhenComparisonIsNull() {
        SignalCheckResult result = detector.detect(property(), null);

        assertThat(result.status()).isEqualTo(RiskCheckStatus.UNDETERMINABLE);
        assertThat(result.reason()).isEqualTo(RiskCheckReason.NO_COMPARABLE_TRANSACTION);
    }

    @Test
    @DisplayName("시세비교가 표본부족으로 판정불가면 NO_COMPARABLE_TRANSACTION으로 매핑한다")
    void detectMapsInsufficientSampleToNoComparableTransaction() {
        SignalCheckResult result = detector.detect(property(), undeterminable(MarketUnavailableReason.INSUFFICIENT_SAMPLE));

        assertThat(result.status()).isEqualTo(RiskCheckStatus.UNDETERMINABLE);
        assertThat(result.reason()).isEqualTo(RiskCheckReason.NO_COMPARABLE_TRANSACTION);
    }

    @Test
    @DisplayName("시세비교가 주소정보부족으로 판정불가면 ADDRESS_INFO_MISSING으로 매핑한다")
    void detectMapsAddressInfoMissing() {
        SignalCheckResult result = detector.detect(property(), undeterminable(MarketUnavailableReason.ADDRESS_INFO_MISSING));

        assertThat(result.status()).isEqualTo(RiskCheckStatus.UNDETERMINABLE);
        assertThat(result.reason()).isEqualTo(RiskCheckReason.ADDRESS_INFO_MISSING);
    }

    @Test
    @DisplayName("시세비교가 매물유형(단독/다가구) 미지원으로 판정불가면 PROPERTY_TYPE_UNSUPPORTED로 매핑한다")
    void detectMapsPropertyTypeUnsupported() {
        SignalCheckResult result = detector.detect(property(), undeterminable(MarketUnavailableReason.PROPERTY_TYPE_UNSUPPORTED));

        assertThat(result.status()).isEqualTo(RiskCheckStatus.UNDETERMINABLE);
        assertThat(result.reason()).isEqualTo(RiskCheckReason.PROPERTY_TYPE_UNSUPPORTED);
    }

    // 회귀 테스트 - 예전에는 이 사유도 PROPERTY_TYPE_UNSUPPORTED로 뭉뚱그려져서, 월세 매물인데
    // "매물 유형을 지원하지 않는다"는 부정확한 안내가 나갔다(risk-analysis-design.md 전수조사 결과
    // 버그 2번). 거래유형 미지원 전용 사유를 분리해 정확한 원인이 내려가는지 확인한다.
    @Test
    @DisplayName("시세비교가 거래유형(월세) 미지원으로 판정불가면 TRANSACTION_TYPE_UNSUPPORTED로 매핑한다")
    void detectMapsTransactionTypeUnsupported() {
        SignalCheckResult result = detector.detect(property(), undeterminable(MarketUnavailableReason.TRANSACTION_TYPE_UNSUPPORTED));

        assertThat(result.status()).isEqualTo(RiskCheckStatus.UNDETERMINABLE);
        assertThat(result.reason()).isEqualTo(RiskCheckReason.TRANSACTION_TYPE_UNSUPPORTED);
    }

    @Test
    @DisplayName("시세비교가 실패(FAILED)면 DATA_FETCH_FAILURE로 매핑해 FAILED를 반환한다")
    void detectMapsFailedComparisonToDataFetchFailure() {
        SignalCheckResult result = detector.detect(property(), failed(MarketUnavailableReason.EXTERNAL_API_FAILURE));

        assertThat(result.status()).isEqualTo(RiskCheckStatus.FAILED);
        assertThat(result.reason()).isEqualTo(RiskCheckReason.DATA_FETCH_FAILURE);
    }

    @Test
    @DisplayName("시세 대비 10% 이상 저렴하면(임계값) SUCCESS와 설명을 반환한다")
    void detectReturnsSuccessWithDescriptionAtThreshold() {
        ReflectionTestUtils.setField(policyConfig, "priceAnomalyPercent", 10);

        SignalCheckResult result = detector.detect(property(), success(-0.10));

        assertThat(result.status()).isEqualTo(RiskCheckStatus.SUCCESS);
        assertThat(result.description()).isEqualTo("시세보다 10% 낮은 가격이에요");
    }

    @Test
    @DisplayName("시세 대비 20% 저렴하면 실제 비율을 담아 설명을 반환한다")
    void detectReturnsSuccessWithActualPercentInDescription() {
        ReflectionTestUtils.setField(policyConfig, "priceAnomalyPercent", 10);

        SignalCheckResult result = detector.detect(property(), success(-0.20));

        assertThat(result.description()).isEqualTo("시세보다 20% 낮은 가격이에요");
    }

    @Test
    @DisplayName("시세 대비 10% 미만으로 저렴하면 SUCCESS와 null 설명(리스크 없음)을 반환한다")
    void detectReturnsSuccessWithNullDescriptionWhenBelowThreshold() {
        ReflectionTestUtils.setField(policyConfig, "priceAnomalyPercent", 10);

        SignalCheckResult result = detector.detect(property(), success(-0.05));

        assertThat(result.status()).isEqualTo(RiskCheckStatus.SUCCESS);
        assertThat(result.description()).isNull();
    }

    @Test
    @DisplayName("시세보다 오히려 비싸면 SUCCESS와 null 설명(리스크 없음)을 반환한다")
    void detectReturnsSuccessWithNullDescriptionWhenMoreExpensive() {
        SignalCheckResult result = detector.detect(property(), success(0.05));

        assertThat(result.status()).isEqualTo(RiskCheckStatus.SUCCESS);
        assertThat(result.description()).isNull();
    }
}
