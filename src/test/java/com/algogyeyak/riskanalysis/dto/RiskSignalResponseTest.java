package com.algogyeyak.riskanalysis.dto;

import com.algogyeyak.property.entity.Property;
import com.algogyeyak.property.entity.PropertyType;
import com.algogyeyak.property.entity.TransactionType;
import com.algogyeyak.riskanalysis.entity.PropertyRisk;
import com.algogyeyak.riskanalysis.entity.PropertyRiskCheck;
import com.algogyeyak.riskanalysis.enums.RiskCheckReason;
import com.algogyeyak.riskanalysis.enums.RiskCheckStatus;
import com.algogyeyak.riskanalysis.enums.RiskSignalType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RiskSignalResponse")
class RiskSignalResponseTest {

    private Property property() {
        return Property.builder()
                .userId(1L)
                .title("테스트 매물")
                .propertyType(PropertyType.OFFICETEL)
                .transactionType(TransactionType.JEONSE)
                .deposit(10_000_000L)
                .area(20.0)
                .build();
    }

    @Test
    @DisplayName("리스크가 발견된 경우 설명을 포함해 담는다")
    void fromIncludesDescriptionWhenRiskFound() {
        PropertyRiskCheck check = PropertyRiskCheck.success(property(), RiskSignalType.DUPLICATE_LISTING, "v1.0");
        PropertyRisk risk = PropertyRisk.of(property(), RiskSignalType.DUPLICATE_LISTING, "동일 주소로 등록된 다른 매물이 있어요");

        RiskSignalResponse response = RiskSignalResponse.from(check, risk);

        assertThat(response.signalType()).isEqualTo(RiskSignalType.DUPLICATE_LISTING);
        assertThat(response.status()).isEqualTo(RiskCheckStatus.SUCCESS);
        assertThat(response.reason()).isNull();
        assertThat(response.description()).isEqualTo("동일 주소로 등록된 다른 매물이 있어요");
    }

    @Test
    @DisplayName("리스크가 없는 경우(PropertyRisk 없음) 설명은 null로 담는다")
    void fromHasNullDescriptionWhenNoRisk() {
        PropertyRiskCheck check = PropertyRiskCheck.success(property(), RiskSignalType.DUPLICATE_LISTING, "v1.0");

        RiskSignalResponse response = RiskSignalResponse.from(check, null);

        assertThat(response.status()).isEqualTo(RiskCheckStatus.SUCCESS);
        assertThat(response.description()).isNull();
    }

    @Test
    @DisplayName("판정 불가 상태면 사유를 담는다")
    void fromIncludesReasonWhenUndeterminable() {
        PropertyRiskCheck check = PropertyRiskCheck.undeterminable(
                property(), RiskSignalType.PRICE_ANOMALY, RiskCheckReason.NO_COMPARABLE_TRANSACTION, "v1.0");

        RiskSignalResponse response = RiskSignalResponse.from(check, null);

        assertThat(response.status()).isEqualTo(RiskCheckStatus.UNDETERMINABLE);
        assertThat(response.reason()).isEqualTo(RiskCheckReason.NO_COMPARABLE_TRANSACTION);
        assertThat(response.description()).isNull();
    }

    @Test
    @DisplayName("PRICE_ANOMALY 리스크가 발견되면 후속 행동 안내를 담는다")
    void fromIncludesRecommendedActionsForPriceAnomalyWhenRiskFound() {
        PropertyRiskCheck check = PropertyRiskCheck.success(property(), RiskSignalType.PRICE_ANOMALY, "v1.0");
        PropertyRisk risk = PropertyRisk.of(property(), RiskSignalType.PRICE_ANOMALY, "시세보다 15% 낮은 가격이에요");

        RiskSignalResponse response = RiskSignalResponse.from(check, risk);

        assertThat(response.recommendedActions()).containsExactly(
                "동일 지역 최근 실거래가 확인",
                "등기부등본 확인",
                "주변 동일 면적 매물 가격 비교",
                "중개사에게 가격이 낮은 이유 확인",
                "옵션/관리비 등 추가 비용 확인"
        );
    }

    @Test
    @DisplayName("PRICE_ANOMALY라도 리스크가 발견되지 않으면 후속 행동 안내는 빈 목록이다")
    void fromHasEmptyRecommendedActionsWhenPriceAnomalyHasNoRisk() {
        PropertyRiskCheck check = PropertyRiskCheck.success(property(), RiskSignalType.PRICE_ANOMALY, "v1.0");

        RiskSignalResponse response = RiskSignalResponse.from(check, null);

        assertThat(response.recommendedActions()).isEmpty();
    }

    @Test
    @DisplayName("PRICE_ANOMALY가 아닌 신호는 리스크가 발견돼도 후속 행동 안내가 빈 목록이다")
    void fromHasEmptyRecommendedActionsForOtherSignalTypes() {
        PropertyRiskCheck check = PropertyRiskCheck.success(property(), RiskSignalType.DUPLICATE_LISTING, "v1.0");
        PropertyRisk risk = PropertyRisk.of(property(), RiskSignalType.DUPLICATE_LISTING, "동일 주소로 등록된 다른 매물이 있어요");

        RiskSignalResponse response = RiskSignalResponse.from(check, risk);

        assertThat(response.recommendedActions()).isEmpty();
    }
}
