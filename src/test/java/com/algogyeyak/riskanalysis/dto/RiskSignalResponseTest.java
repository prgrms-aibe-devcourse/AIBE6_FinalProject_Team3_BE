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
}
