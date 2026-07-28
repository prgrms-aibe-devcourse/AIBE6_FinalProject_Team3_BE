package com.algogyeyak.checklist.entity;

import com.algogyeyak.global.error.ErrorCode;
import com.algogyeyak.global.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ChecklistItem")
class ChecklistItemTest {

    private ChecklistItem checkTypeItem() {
        return ChecklistItem.builder()
                .category(ChecklistCategory.INDOOR)
                .content("벽면에 누수 흔적이 없나요?")
                .importance(ChecklistImportance.GENERAL)
                .itemType(ChecklistItemType.CHECK)
                .displayOrder(1)
                .build();
    }

    @Test
    @DisplayName("check(true) 호출 시 CHECK 타입 문항이 확인 상태가 된다")
    void checkMarksCheckTypeItemAsChecked() {
        ChecklistItem item = checkTypeItem();

        item.check(true);

        assertThat(item.isChecked()).isTrue();
        assertThat(item.isIssueFound()).isFalse();
    }

    @Test
    @DisplayName("CHECK 타입 문항에 answer()를 호출하면 BAD_REQUEST 예외가 발생한다")
    void answerRejectsCheckTypeItem() {
        ChecklistItem item = checkTypeItem();

        assertThatThrownBy(() -> item.answer("Y"))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception ->
                        assertThat(((BusinessException) exception).getErrorCode()).isEqualTo(ErrorCode.BAD_REQUEST)
                );
    }

    private ChecklistItem yesNoItem(ChecklistItemCode code) {
        return ChecklistItem.builder()
                .category(ChecklistCategory.DOCUMENTS)
                .content("신탁등기가 되어 있나요?")
                .importance(ChecklistImportance.REQUIRED)
                .itemType(ChecklistItemType.YES_NO)
                .code(code)
                .displayOrder(1)
                .build();
    }

    @Test
    @DisplayName("YES_NO 문항에 Y/N을 답하면 값이 저장되고 확인 상태가 된다")
    void answerYesNoSavesValueAndMarksChecked() {
        ChecklistItem item = yesNoItem(null);

        item.answer("Y");

        assertThat(item.isChecked()).isTrue();
        assertThat(item.getValue()).isEqualTo("Y");
    }

    @Test
    @DisplayName("YES_NO 문항에 Y/N이 아닌 값을 답하면 BAD_REQUEST 예외가 발생한다")
    void answerYesNoRejectsValueOtherThanYOrN() {
        ChecklistItem item = yesNoItem(null);

        assertThatThrownBy(() -> item.answer("MAYBE"))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception ->
                        assertThat(((BusinessException) exception).getErrorCode()).isEqualTo(ErrorCode.BAD_REQUEST)
                );
    }

    @Test
    @DisplayName("신탁등기 여부에 Y로 답하면 자동으로 주의 항목(issueFound)이 된다")
    void trustRegistrationYesMarksIssueFound() {
        ChecklistItem item = yesNoItem(ChecklistItemCode.TRUST_REGISTRATION);

        item.answer("Y");

        assertThat(item.isIssueFound()).isTrue();
    }

    @Test
    @DisplayName("신탁등기 여부를 Y로 답했다가 N으로 정정하면 주의 항목 표시가 풀린다")
    void correctingAnswerFromYesToNoClearsIssueFound() {
        ChecklistItem item = yesNoItem(ChecklistItemCode.TRUST_REGISTRATION);

        item.answer("Y");
        item.answer("N");

        assertThat(item.isIssueFound()).isFalse();
    }

    @Test
    @DisplayName("신탁등기 여부에 N으로 답하면 주의 항목으로 반영되지 않는다")
    void trustRegistrationNoDoesNotMarkIssueFound() {
        ChecklistItem item = yesNoItem(ChecklistItemCode.TRUST_REGISTRATION);

        item.answer("N");

        assertThat(item.isIssueFound()).isFalse();
    }

    @Test
    @DisplayName("소유자-임대인 명의가 불일치(N)하면 자동으로 주의 항목이 된다")
    void ownershipMismatchMarksIssueFound() {
        ChecklistItem item = yesNoItem(ChecklistItemCode.OWNERSHIP_MATCH);

        // "N" = 소유자와 임대인 명의가 일치하지 않음 (명의 불일치)
        item.answer("N");

        assertThat(item.isIssueFound()).isTrue();
    }

    @Test
    @DisplayName("소유자-임대인 명의가 일치(Y)하면 주의 항목으로 반영되지 않는다")
    void ownershipMatchDoesNotMarkIssueFound() {
        ChecklistItem item = yesNoItem(ChecklistItemCode.OWNERSHIP_MATCH);

        item.answer("Y");

        assertThat(item.isIssueFound()).isFalse();
    }

    private ChecklistItem dateItem() {
        return ChecklistItem.builder()
                .category(ChecklistCategory.DOCUMENTS)
                .content("소유권 취득일을 확인했나요?")
                .importance(ChecklistImportance.REQUIRED)
                .itemType(ChecklistItemType.DATE)
                .code(ChecklistItemCode.OWNERSHIP_ACQUISITION_DATE)
                .displayOrder(1)
                .build();
    }

    @Test
    @DisplayName("DATE 문항에 yyyy-MM-dd 형식으로 답하면 값이 저장되고 확인 상태가 된다")
    void answerDateSavesValueAndMarksChecked() {
        ChecklistItem item = dateItem();

        item.answer("2026-07-01");

        assertThat(item.isChecked()).isTrue();
        assertThat(item.getValue()).isEqualTo("2026-07-01");
        assertThat(item.isIssueFound()).isFalse();
    }

    @Test
    @DisplayName("DATE 문항에 yyyy-MM-dd 형식이 아닌 값을 답하면 BAD_REQUEST 예외가 발생한다")
    void answerDateRejectsInvalidFormat() {
        ChecklistItem item = dateItem();

        assertThatThrownBy(() -> item.answer("2026/07/01"))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception ->
                        assertThat(((BusinessException) exception).getErrorCode()).isEqualTo(ErrorCode.BAD_REQUEST)
                );
    }

    private ChecklistItem documentRequestItem(ChecklistItemCode code) {
        return ChecklistItem.builder()
                .category(ChecklistCategory.DOCUMENTS)
                .content("확정일자 부여현황을 임대인에게 요청했나요?")
                .importance(ChecklistImportance.REQUIRED)
                .itemType(ChecklistItemType.DOCUMENT_REQUEST)
                .code(code)
                .displayOrder(1)
                .build();
    }

    @Test
    @DisplayName("DOCUMENT_REQUEST 문항에 PROVIDED로 답하면 확인 상태가 되고 주의 항목으로 반영되지 않는다")
    void answerDocumentRequestProvidedDoesNotMarkIssueFound() {
        ChecklistItem item = documentRequestItem(ChecklistItemCode.DATE_OF_CONFIRMATION_REQUEST);

        item.answer("PROVIDED");

        assertThat(item.isChecked()).isTrue();
        assertThat(item.getValue()).isEqualTo("PROVIDED");
        assertThat(item.isIssueFound()).isFalse();
    }

    @Test
    @DisplayName("DOCUMENT_REQUEST 문항에 NOT_PROVIDED로 답하면 자동으로 주의 항목이 된다")
    void answerDocumentRequestNotProvidedMarksIssueFound() {
        ChecklistItem item = documentRequestItem(ChecklistItemCode.RESIDENT_REGISTRATION_REQUEST);

        item.answer("NOT_PROVIDED");

        assertThat(item.isChecked()).isTrue();
        assertThat(item.isIssueFound()).isTrue();
    }

    @Test
    @DisplayName("DOCUMENT_REQUEST 문항에 PROVIDED/NOT_PROVIDED가 아닌 값을 답하면 BAD_REQUEST 예외가 발생한다")
    void answerDocumentRequestRejectsInvalidValue() {
        ChecklistItem item = documentRequestItem(ChecklistItemCode.DATE_OF_CONFIRMATION_REQUEST);

        assertThatThrownBy(() -> item.answer("MAYBE"))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception ->
                        assertThat(((BusinessException) exception).getErrorCode()).isEqualTo(ErrorCode.BAD_REQUEST)
                );
    }

    @Test
    @DisplayName("CHECK 항목에 markInsufficient()를 호출하면 확인 상태가 되고 메모가 저장된다")
    void markInsufficientSavesNoteAndMarksChecked() {
        ChecklistItem item = checkTypeItem();

        item.markInsufficient("환기구가 막혀있었어요");

        assertThat(item.isChecked()).isTrue();
        assertThat(item.getUserNote()).isEqualTo("환기구가 막혀있었어요");
    }

    @Test
    @DisplayName("빈 문자열 메모도 허용된다(미흡 표시만, 메모 내용 없음)")
    void markInsufficientAllowsEmptyNote() {
        ChecklistItem item = checkTypeItem();

        item.markInsufficient("");

        assertThat(item.isChecked()).isTrue();
        assertThat(item.getUserNote()).isEqualTo("");
    }

    @Test
    @DisplayName("CHECK가 아닌 항목에 markInsufficient()를 호출하면 BAD_REQUEST 예외가 발생한다")
    void markInsufficientRejectsNonCheckType() {
        ChecklistItem item = yesNoItem(null);

        assertThatThrownBy(() -> item.markInsufficient("메모"))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception ->
                        assertThat(((BusinessException) exception).getErrorCode()).isEqualTo(ErrorCode.BAD_REQUEST)
                );
    }

    @Test
    @DisplayName("미흡 표시 후 check(true)로 완료 처리하면 메모가 지워진다")
    void completingAfterInsufficientClearsNote() {
        ChecklistItem item = checkTypeItem();
        item.markInsufficient("확인 필요");

        item.check(true);

        assertThat(item.isChecked()).isTrue();
        assertThat(item.getUserNote()).isNull();
    }

    @Test
    @DisplayName("check(false)로 미확인 처리하면 메모도 함께 지워진다")
    void uncheckingClearsNote() {
        ChecklistItem item = checkTypeItem();
        item.markInsufficient("확인 필요");

        item.check(false);

        assertThat(item.isChecked()).isFalse();
        assertThat(item.getUserNote()).isNull();
    }

    @Test
    @DisplayName("CHECK가 아닌 항목에 check()를 호출하면 BAD_REQUEST 예외가 발생한다")
    void checkRejectsNonCheckType() {
        ChecklistItem item = yesNoItem(null);

        assertThatThrownBy(() -> item.check(true))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception ->
                        assertThat(((BusinessException) exception).getErrorCode()).isEqualTo(ErrorCode.BAD_REQUEST)
                );
    }

    @Test
    @DisplayName("markInsufficient()에 null을 전달하면 BAD_REQUEST 예외가 발생한다")
    void markInsufficientRejectsNullNote() {
        ChecklistItem item = checkTypeItem();

        assertThatThrownBy(() -> item.markInsufficient(null))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception ->
                        assertThat(((BusinessException) exception).getErrorCode()).isEqualTo(ErrorCode.BAD_REQUEST)
                );
    }
}
