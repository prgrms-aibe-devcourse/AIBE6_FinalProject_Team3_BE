package com.algogyeyak.contractanalysis.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.algogyeyak.contractanalysis.dto.ContractAnalysisMaskingRequest;
import com.algogyeyak.contractanalysis.dto.ContractAnalysisMaskingResponse;
import com.algogyeyak.global.error.ErrorCode;
import com.algogyeyak.global.exception.BusinessException;
import org.junit.jupiter.api.Test;

class ContractAnalysisMaskingServiceTest {

    private final ContractAnalysisMaskingService service = new ContractAnalysisMaskingService();

    private ContractAnalysisMaskingResponse mask(String text) {
        return service.mask(new ContractAnalysisMaskingRequest(text));
    }

    @Test
    void maskThrowsInvalidInputWhenTextIsEmpty() {
        BusinessException exception = assertThrows(BusinessException.class, () -> mask(""));

        assertEquals(ErrorCode.CONTRACT_ANALYSIS_INVALID_INPUT, exception.getErrorCode());
    }

    @Test
    void maskThrowsInvalidInputWhenTextIsBlank() {
        BusinessException exception = assertThrows(BusinessException.class, () -> mask("   "));

        assertEquals(ErrorCode.CONTRACT_ANALYSIS_INVALID_INPUT, exception.getErrorCode());
    }

    @Test
    void maskThrowsInvalidInputWhenTextIsNull() {
        BusinessException exception = assertThrows(BusinessException.class, () -> mask(null));

        assertEquals(ErrorCode.CONTRACT_ANALYSIS_INVALID_INPUT, exception.getErrorCode());
    }

    // ---------- 1. 주민등록번호 ----------

    @Test
    void maskResidentRegistrationNumberWithDash() {
        ContractAnalysisMaskingResponse response = mask("주민등록번호 901231-1234567 입니다.");

        assertEquals("주민등록번호 [주민등록번호] 입니다.", response.maskedText());
        assertEquals(1, response.maskedCount());
    }

    @Test
    void maskResidentRegistrationNumberWithoutSeparator() {
        ContractAnalysisMaskingResponse response = mask("주민번호: 9012311234567");

        assertEquals("주민번호: [주민등록번호]", response.maskedText());
        assertEquals(1, response.maskedCount());
    }

    @Test
    void maskResidentRegistrationNumberBoundaryGenderDigitFour() {
        // [1-4] 범위의 상한 경계값
        ContractAnalysisMaskingResponse response = mask("901231-4234567");

        assertEquals("[주민등록번호]", response.maskedText());
        assertEquals(1, response.maskedCount());
    }

    @Test
    void doesNotMaskResidentRegistrationNumberWhenGenderDigitOutOfRange() {
        // 성별 코드는 1~4만 유효하므로 5는 매칭되면 안 된다.
        ContractAnalysisMaskingResponse response = mask("901231-5234567");

        assertEquals("901231-5234567", response.maskedText());
        assertEquals(0, response.maskedCount());
    }

    // ---------- 2. 전화번호 ----------

    @Test
    void maskPhoneNumberWithDashes() {
        ContractAnalysisMaskingResponse response = mask("연락처 010-1234-5678 입니다.");

        assertEquals("연락처 [전화번호] 입니다.", response.maskedText());
        assertEquals(1, response.maskedCount());
    }

    @Test
    void maskPhoneNumberWithoutSeparator() {
        ContractAnalysisMaskingResponse response = mask("01012345678");

        assertEquals("[전화번호]", response.maskedText());
        assertEquals(1, response.maskedCount());
    }

    @Test
    void maskPhoneNumberWithThreeDigitMiddleBlock() {
        ContractAnalysisMaskingResponse response = mask("011-123-4567");

        assertEquals("[전화번호]", response.maskedText());
        assertEquals(1, response.maskedCount());
    }

    @Test
    void doesNotMaskPhoneNumberWhenPrefixNotAllowed() {
        // 01[016789]에 없는 012는 허용되지 않는 통신사 접두어다.
        ContractAnalysisMaskingResponse response = mask("012-1234-5678");

        assertEquals("012-1234-5678", response.maskedText());
        assertEquals(0, response.maskedCount());
    }

    // ---------- 3. 계좌번호 (라벨 기반) ----------

    @Test
    void maskAccountNumberKeepsLabelColon() {
        ContractAnalysisMaskingResponse response = mask("계좌: 123-4567-8901");

        assertEquals("계좌: [계좌번호]", response.maskedText());
        assertEquals(1, response.maskedCount());
    }

    @Test
    void maskAccountNumberWithBankLabelWord() {
        ContractAnalysisMaskingResponse response = mask("국민은행 123456-78-901234로 입금해주세요");

        assertEquals("국민은행 [계좌번호]로 입금해주세요", response.maskedText());
        assertEquals(1, response.maskedCount());
    }

    @Test
    void maskAccountNumberAtMinimumLengthBoundary() {
        // \d[\d\-\s]{8,20}\d 에서 중간 구간이 정확히 8자인 경계값
        ContractAnalysisMaskingResponse response = mask("계좌: 12-3456-78");

        assertEquals("계좌: [계좌번호]", response.maskedText());
        assertEquals(1, response.maskedCount());
    }

    @Test
    void doesNotMaskAccountNumberWhenDigitRunTooShort() {
        // 중간 구간이 8자 미만이라 계좌번호로 인식되면 안 된다.
        ContractAnalysisMaskingResponse response = mask("계좌: 12-3456");

        assertEquals("계좌: 12-3456", response.maskedText());
        assertEquals(0, response.maskedCount());
    }

    @Test
    void doesNotMaskNumberWithoutAccountLabel() {
        // 라벨이 없으면 계좌번호로 보이는 숫자열이라도 마스킹하지 않는다.
        ContractAnalysisMaskingResponse response = mask("123-4567-8901");

        assertEquals("123-4567-8901", response.maskedText());
        assertEquals(0, response.maskedCount());
    }

    // ---------- 4. 임대인/임차인 성명 (라벨 기반) ----------

    @Test
    void maskLandlordNameAfterColonKeepsLabel() {
        // 라벨-이름 사이 필러가 lazy가 아니면 이름 앞글자("홍")가 새어나가는 회귀 테스트
        ContractAnalysisMaskingResponse response = mask("임대인: 홍길동");

        assertEquals("임대인: [성명]", response.maskedText());
        assertEquals(1, response.maskedCount());
    }

    @Test
    void maskTenantNameInParenthesesKeepsLabel() {
        ContractAnalysisMaskingResponse response = mask("임차인(김철수)");

        assertEquals("임차인([성명])", response.maskedText());
        assertEquals(1, response.maskedCount());
    }

    @Test
    void maskFourCharacterNameFully() {
        ContractAnalysisMaskingResponse response = mask("임차인: 남궁민수");

        assertEquals("임차인: [성명]", response.maskedText());
        assertEquals(1, response.maskedCount());
    }

    @Test
    void maskTwoCharacterNameBoundary() {
        ContractAnalysisMaskingResponse response = mask("임대인: 이씨");

        assertEquals("임대인: [성명]", response.maskedText());
        assertEquals(1, response.maskedCount());
    }

    @Test
    void doesNotMaskWhenNameShorterThanTwoCharacters() {
        // [가-힣]{2,4}의 하한 미만이라 매칭되면 안 된다.
        ContractAnalysisMaskingResponse response = mask("임대인: 이");

        assertEquals("임대인: 이", response.maskedText());
        assertEquals(0, response.maskedCount());
    }

    @Test
    void doesNotMaskWhenLabelIsFollowedByParticleWithoutDelimiter() {
        // "임차인은"/"임대인의"처럼 라벨에 조사가 바로 붙는 경우, 구분자(콜론/괄호)나
        // 공백 없이 이어지므로 뒤따르는 일반 명사("계약", "동의")를 이름으로 오인해서는 안 된다.
        ContractAnalysisMaskingResponse response =
                mask("제3조 임차인은 계약 기간 중 임대인의 동의 없이 반려동물을 키울 수 없다.");

        assertEquals("제3조 임차인은 계약 기간 중 임대인의 동의 없이 반려동물을 키울 수 없다.", response.maskedText());
        assertEquals(0, response.maskedCount());
    }

    // ---------- 처리 순서 / 충돌 방지 ----------

    @Test
    void maskAppliesAllPatternsInOrderWithoutConflict() {
        String text = "임대인 홍길동(주민등록번호 901231-1234567, 연락처 010-1234-5678)과 "
                + "임차인 김철수는 국민은행 계좌: 123-4567-8901 로 입금하기로 한다.";

        ContractAnalysisMaskingResponse response = mask(text);

        assertEquals(
                "임대인 [성명](주민등록번호 [주민등록번호], 연락처 [전화번호])과 "
                        + "임차인 [성명] 국민은행 계좌: [계좌번호] 로 입금하기로 한다.",
                response.maskedText()
        );
        assertEquals(5, response.maskedCount());
        assertEquals(true, response.requiresUserConfirmation());
    }

    @Test
    void maskReturnsZeroCountAndOriginalTextWhenNothingToMask() {
        String text = "이 문서는 임대차 계약의 일반 조항을 설명합니다.";

        ContractAnalysisMaskingResponse response = mask(text);

        assertEquals(text, response.maskedText());
        assertEquals(0, response.maskedCount());
        assertEquals(true, response.requiresUserConfirmation());
    }

    @Test
    void requiresUserConfirmationIsAlwaysTrue() {
        ContractAnalysisMaskingResponse response = mask("아무 개인정보도 없는 평범한 문장입니다.");

        assertEquals(true, response.requiresUserConfirmation());
    }
}
