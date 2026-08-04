package com.algogyeyak.riskanalysis.dto;

import com.algogyeyak.property.entity.Property;
import com.algogyeyak.property.entity.PropertyType;
import com.algogyeyak.property.entity.TransactionType;
import com.algogyeyak.riskanalysis.entity.DepositSafetyCheck;
import com.algogyeyak.riskanalysis.enums.DepositSafetyCheckReason;
import com.algogyeyak.riskanalysis.enums.DepositSafetyStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("DepositSafetyCheckResponse")
class DepositSafetyCheckResponseTest {

    private Property property(Long id) {
        Property property = Property.builder()
                .userId(1L)
                .propertyType(PropertyType.OFFICETEL)
                .transactionType(TransactionType.JEONSE)
                .deposit(200_000_000L)
                .area(20.0)
                .build();
        ReflectionTestUtils.setField(property, "id", id);
        return property;
    }

    @Test
    @DisplayName("계산된 경우 전세가율/설명/기준일과 선순위보증금 미반영 여부를 담는다")
    void fromCalculatedIncludesRatioAndExplanation() {
        DepositSafetyCheck check = DepositSafetyCheck.calculated(
                property(10L), BigDecimal.valueOf(82), null, null,
                LocalDate.of(2026, 7, 31), "이 집 전세가율은 82%예요.", "v1.0");

        DepositSafetyCheckResponse response = DepositSafetyCheckResponse.from(10L, check);

        assertThat(response.propertyId()).isEqualTo(10L);
        assertThat(response.status()).isEqualTo(DepositSafetyStatus.CALCULATED);
        assertThat(response.jeonseRatio()).isEqualTo(82);
        assertThat(response.seniorDepositApplied()).isFalse();
        assertThat(response.explanation()).isEqualTo("이 집 전세가율은 82%예요.");
        assertThat(response.referenceDate()).isEqualTo(LocalDate.of(2026, 7, 31));
        assertThat(response.reason()).isNull();
        assertThat(response.disclaimer()).isEqualTo(DepositSafetyCheckResponse.DISCLAIMER);
    }

    @Test
    @DisplayName("선순위보증금이 반영된 경우 seniorDepositApplied와 값들을 함께 담는다")
    void fromCalculatedWithSeniorDepositApplied() {
        DepositSafetyCheck check = DepositSafetyCheck.calculated(
                property(10L), BigDecimal.valueOf(95), BigDecimal.valueOf(50_000_000L), BigDecimal.valueOf(10_000_000L),
                LocalDate.of(2026, 7, 31), "이 집 전세가율은 95%예요.", "v1.0");

        DepositSafetyCheckResponse response = DepositSafetyCheckResponse.from(10L, check);

        assertThat(response.seniorDepositApplied()).isTrue();
        assertThat(response.seniorDeposit()).isEqualTo(50_000_000L);
        assertThat(response.maxClaimAmount()).isEqualTo(10_000_000L);
    }

    @Test
    @DisplayName("판정 불가인 경우 사유를 담고 수치 관련 필드는 null로 둔다")
    void fromUnavailableIncludesReason() {
        DepositSafetyCheck check = DepositSafetyCheck.unavailable(
                property(10L), null, null, DepositSafetyCheckReason.ESTIMATED_PRICE_MISSING, "v1.0");

        DepositSafetyCheckResponse response = DepositSafetyCheckResponse.from(10L, check);

        assertThat(response.status()).isEqualTo(DepositSafetyStatus.UNAVAILABLE);
        assertThat(response.reason()).isEqualTo(DepositSafetyCheckReason.ESTIMATED_PRICE_MISSING);
        assertThat(response.jeonseRatio()).isNull();
        assertThat(response.explanation()).isNull();
        assertThat(response.referenceDate()).isNull();
        assertThat(response.seniorDepositApplied()).isFalse();
    }

    @Test
    @DisplayName("체크 결과가 아직 없으면(null) 미계산 상태로 담는다")
    void fromNullChecksIsNotCalculatedYet() {
        DepositSafetyCheckResponse response = DepositSafetyCheckResponse.from(10L, null);

        assertThat(response.propertyId()).isEqualTo(10L);
        assertThat(response.status()).isNull();
        assertThat(response.jeonseRatio()).isNull();
    }
}
