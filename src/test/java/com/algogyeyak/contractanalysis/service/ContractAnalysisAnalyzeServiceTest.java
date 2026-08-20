package com.algogyeyak.contractanalysis.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.algogyeyak.contractanalysis.client.GeminiClient;
import com.algogyeyak.contractanalysis.client.dto.GeminiGenerateContentResponse;
import com.algogyeyak.contractanalysis.dto.ContractAnalysisAnalyzeRequest;
import com.algogyeyak.contractanalysis.dto.ContractAnalysisAnalyzeResponse;
import com.algogyeyak.contractanalysis.entity.InputType;
import com.algogyeyak.contractanalysis.repository.ContractRequestRepository;
import com.algogyeyak.global.error.ErrorCode;
import com.algogyeyak.global.exception.BusinessException;
import java.util.List;
import org.junit.jupiter.api.Test;

class ContractAnalysisAnalyzeServiceTest {

    private static final String MASKED_TEXT =
            "제3조 임차인은 계약 기간 중 임대인의 동의 없이 반려동물을 키울 수 없다. "
                    + "제5조 보증금은 계약 종료 후 즉시 반환하지 않고 다음 세입자 입주 시 반환한다.";

    private static final Long USER_ID = 1L;

    private final GeminiClient geminiClient = mock(GeminiClient.class);
    private final ContractAnalysisMaskingService maskingService = new ContractAnalysisMaskingService();
    private final ContractRequestRepository contractRequestRepository = mock(ContractRequestRepository.class);
    private final ContractAnalysisAnalyzeService service =
            new ContractAnalysisAnalyzeService(geminiClient, maskingService, contractRequestRepository);

    private ContractAnalysisAnalyzeResponse analyze(String maskedText, Boolean userConfirmed) {
        return service.analyze(USER_ID, new ContractAnalysisAnalyzeRequest(maskedText, userConfirmed, null, InputType.TEXT));
    }

    private GeminiGenerateContentResponse responseWithText(String text) {
        return new GeminiGenerateContentResponse(
                List.of(new GeminiGenerateContentResponse.Candidate(
                        new GeminiGenerateContentResponse.Content(
                                List.of(new GeminiGenerateContentResponse.Part(text)),
                                "model"
                        ),
                        "STOP"
                ))
        );
    }

    private String clauseJson(String originalText, boolean riskFlag, String explanation, String question, String suggestedText) {
        return """
                {"clauses": [{
                  "originalText": "%s",
                  "riskFlag": %s,
                  "explanation": "%s",
                  "question": "%s",
                  "suggestedText": "%s"
                }]}
                """.formatted(originalText, riskFlag, explanation, question, suggestedText);
    }

    // ---------- userConfirmed / 입력 검증 ----------

    @Test
    void analyzeThrowsMaskingNotConfirmedWhenUserConfirmedIsFalse() {
        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> analyze(MASKED_TEXT, false)
        );

        assertEquals(ErrorCode.CONTRACT_ANALYSIS_MASKING_NOT_CONFIRMED, exception.getErrorCode());
    }

    @Test
    void analyzeThrowsMaskingNotConfirmedWhenUserConfirmedIsNull() {
        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> analyze(MASKED_TEXT, null)
        );

        assertEquals(ErrorCode.CONTRACT_ANALYSIS_MASKING_NOT_CONFIRMED, exception.getErrorCode());
    }

    @Test
    void analyzeThrowsInvalidInputWhenMaskedTextIsBlank() {
        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> analyze("   ", true)
        );

        assertEquals(ErrorCode.CONTRACT_ANALYSIS_INVALID_INPUT, exception.getErrorCode());
    }

    @Test
    void analyzeChecksConfirmationBeforeInputSoBothMissingReportsNotConfirmed() {
        // userConfirmed 검증이 먼저이므로, 텍스트도 비어있는 최악의 경우에도 MASKING_NOT_CONFIRMED가 먼저 나와야 한다.
        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> analyze("", false)
        );

        assertEquals(ErrorCode.CONTRACT_ANALYSIS_MASKING_NOT_CONFIRMED, exception.getErrorCode());
    }

    // ---------- 마스킹 우회 방지 (마스킹 안 거친 원문 거부) ----------

    @Test
    void analyzeThrowsUnmaskedPiiDetectedWhenMaskedTextContainsResidentRegistrationNumber() {
        // userConfirmed=true만 붙이면 /masking을 실제로 거치지 않은 원문도 통과되던 문제를 막는다.
        String rawTextWithRrn = "임차인 주민등록번호는 901231-1234567 입니다.";

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> analyze(rawTextWithRrn, true)
        );

        assertEquals(ErrorCode.CONTRACT_ANALYSIS_UNMASKED_PII_DETECTED, exception.getErrorCode());
    }

    @Test
    void analyzeThrowsUnmaskedPiiDetectedWhenMaskedTextContainsPhoneNumber() {
        String rawTextWithPhone = "임대인 연락처는 010-1234-5678 입니다.";

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> analyze(rawTextWithPhone, true)
        );

        assertEquals(ErrorCode.CONTRACT_ANALYSIS_UNMASKED_PII_DETECTED, exception.getErrorCode());
    }

    @Test
    void analyzeThrowsUnmaskedPiiDetectedWhenMaskedTextContainsAccountNumber() {
        String rawTextWithAccount = "보증금은 계좌: 123-456-7890123 로 입금한다.";

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> analyze(rawTextWithAccount, true)
        );

        assertEquals(ErrorCode.CONTRACT_ANALYSIS_UNMASKED_PII_DETECTED, exception.getErrorCode());
    }

    @Test
    void analyzeAllowsTextWithMaskingTokensAndNoRawPii() {
        // 정상적으로 마스킹된 텍스트(마스킹 토큰만 남고 실제 PII 패턴은 없음)는 이 검증을 통과해야 한다.
        String properlyMaskedText =
                "임차인 [성명]의 연락처는 [전화번호]이며, 주민등록번호는 [주민등록번호]입니다. "
                        + "제3조 임차인은 계약 기간 중 임대인의 동의 없이 반려동물을 키울 수 없다.";
        String json = clauseJson(
                "제3조 임차인은 계약 기간 중 임대인의 동의 없이 반려동물을 키울 수 없다.",
                false, "설명", "질문", "제안"
        );
        when(geminiClient.analyzeClauses(properlyMaskedText)).thenReturn(responseWithText(json));

        ContractAnalysisAnalyzeResponse response = analyze(properlyMaskedText, true);

        assertEquals(1, response.clauses().size());
    }

    // ---------- AI 응답 스키마 검증 ----------

    @Test
    void analyzeThrowsAiResponseInvalidWhenCandidatesEmpty() {
        when(geminiClient.analyzeClauses(MASKED_TEXT))
                .thenReturn(new GeminiGenerateContentResponse(List.of()));

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> analyze(MASKED_TEXT, true)
        );

        assertEquals(ErrorCode.CONTRACT_ANALYSIS_AI_RESPONSE_INVALID, exception.getErrorCode());
    }

    @Test
    void analyzeThrowsAiResponseInvalidWhenResponseTextIsMalformedJson() {
        when(geminiClient.analyzeClauses(MASKED_TEXT))
                .thenReturn(responseWithText("이건 JSON이 아닙니다"));

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> analyze(MASKED_TEXT, true)
        );

        assertEquals(ErrorCode.CONTRACT_ANALYSIS_AI_RESPONSE_INVALID, exception.getErrorCode());
    }

    @Test
    void analyzeThrowsAiResponseInvalidWhenRequiredFieldIsBlank() {
        // explanation이 빈 문자열 -> 사전 정의 스키마 위반
        String json = clauseJson("제3조 임차인은 계약 기간 중 임대인의 동의 없이 반려동물을 키울 수 없다.", true, "", "질문", "제안");
        when(geminiClient.analyzeClauses(MASKED_TEXT)).thenReturn(responseWithText(json));

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> analyze(MASKED_TEXT, true)
        );

        assertEquals(ErrorCode.CONTRACT_ANALYSIS_AI_RESPONSE_INVALID, exception.getErrorCode());
    }

    @Test
    void analyzeThrowsAiResponseInvalidWhenClausesFieldMissing() {
        when(geminiClient.analyzeClauses(MASKED_TEXT)).thenReturn(responseWithText("{}"));

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> analyze(MASKED_TEXT, true)
        );

        assertEquals(ErrorCode.CONTRACT_ANALYSIS_AI_RESPONSE_INVALID, exception.getErrorCode());
    }

    // ---------- 환각(hallucination) 방지 ----------

    @Test
    void analyzeThrowsAiHallucinationWhenOriginalTextNotInMaskedInput() {
        String json = clauseJson("입력에 전혀 없는 조항 문구입니다", true, "설명", "질문", "제안");
        when(geminiClient.analyzeClauses(MASKED_TEXT)).thenReturn(responseWithText(json));

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> analyze(MASKED_TEXT, true)
        );

        assertEquals(ErrorCode.CONTRACT_ANALYSIS_AI_HALLUCINATION, exception.getErrorCode());
    }

    @Test
    void analyzeThrowsAiHallucinationWhenClauseIsWordsRecombinedFromScatteredLocations() {
        // 아래 11개 단어는 전부 MASKED_TEXT 어딘가에 개별 토큰으로 존재하지만("계약"은 두 문장에
        // 각각 등장), 이 순서로 이어붙인 문장 자체는 원문 어디에도 연속해서 존재하지 않는다.
        // 기존의 순서 무관 단어 집합 비교(11/11 = 100%)였다면 이 허구 조항이 그대로 통과했을
        // 것이다. n-gram(3-word) 비교로 바뀐 뒤에는, 실제로 원문과 연속으로 겹치는 3-gram이
        // "반려동물을 키울 수"와 "키울 수 없다." 단 2개(9개 중)뿐이라 임계값(85%)에 크게
        // 못 미쳐 환각으로 정확히 거부되어야 한다.
        String fabricatedClause = "임차인은 계약 종료 후 즉시 반환하지 않고 반려동물을 키울 수 없다.";
        String json = clauseJson(fabricatedClause, true, "설명", "질문", "제안");
        when(geminiClient.analyzeClauses(MASKED_TEXT)).thenReturn(responseWithText(json));

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> analyze(MASKED_TEXT, true)
        );

        assertEquals(ErrorCode.CONTRACT_ANALYSIS_AI_HALLUCINATION, exception.getErrorCode());
    }

    @Test
    void analyzeAcceptsClauseWhenMaskedTextUsesFullWidthSpaceInsteadOfAsciiSpace() {
        // Clova OCR/HWP 등에서 복사된 계약서 원문에는 U+3000(전각 공백)이 섞여 들어올 수 있다.
        // AI가 원문을 그대로 인용해도 공백 문자 종류가 다르면 정규화 후 비교가 실패해서는 안 된다.
        String maskedTextWithFullWidthSpace =
                "제3조　임차인은 계약 기간 중 임대인의 동의 없이 반려동물을 키울 수 없다.";
        String clauseText = "제3조 임차인은 계약 기간 중 임대인의 동의 없이 반려동물을 키울 수 없다.";
        String json = clauseJson(clauseText, true, "설명", "질문", "제안");
        when(geminiClient.analyzeClauses(maskedTextWithFullWidthSpace)).thenReturn(responseWithText(json));

        ContractAnalysisAnalyzeResponse response = analyze(maskedTextWithFullWidthSpace, true);

        assertEquals(1, response.clauses().size());
    }

    @Test
    void analyzeAcceptsClauseWithSubNumberedArticleTitleGluedToDifferentParagraph() {
        // AI가 "제10조의2(비용의 정산)" 제목을 실제로는 이어져 있지 않은 다른 항(②)에 붙여
        // 반환하는 경우, 제목을 정확히 벗겨내야만 남은 본문("②주차비는...")이 원문에서
        // 그대로 발견되어 정상 조항으로 인정된다. "의N" 가지번호를 인식하지 못하는 예전
        // 정규식이었다면 "의2(비용의 정산)"가 본문에 그대로 남아 이 매칭이 실패했을 것이다.
        String maskedTextWithSubArticle =
                "제10조의2(비용의 정산) ①관리비는 임차인이 부담한다. ②주차비는 별도로 정산한다.";
        String clauseText = "제10조의2(비용의 정산) ②주차비는 별도로 정산한다.";
        String json = clauseJson(clauseText, true, "설명", "질문", "제안");
        when(geminiClient.analyzeClauses(maskedTextWithSubArticle)).thenReturn(responseWithText(json));

        ContractAnalysisAnalyzeResponse response = analyze(maskedTextWithSubArticle, true);

        assertEquals(1, response.clauses().size());
    }

    // ---------- 성공 케이스 ----------

    @Test
    void analyzeSucceedsAndCountsOnlyRiskFlaggedClausesInSummary() {
        String json = """
                {"clauses": [
                  {
                    "originalText": "제3조 임차인은 계약 기간 중 임대인의 동의 없이 반려동물을 키울 수 없다.",
                    "riskFlag": true,
                    "explanation": "반려동물 금지 조항입니다.",
                    "question": "반려동물을 키우실 계획이 있나요?",
                    "suggestedText": "반려동물 사육에 대해 임대인과 별도 협의를 원합니다."
                  },
                  {
                    "originalText": "제5조 보증금은 계약 종료 후 즉시 반환하지 않고 다음 세입자 입주 시 반환한다.",
                    "riskFlag": true,
                    "explanation": "보증금 반환이 지연될 수 있는 조항입니다.",
                    "question": "보증금 반환 시점을 명확히 하고 싶지 않으신가요?",
                    "suggestedText": "보증금은 계약 종료 후 O일 이내 반환하는 것으로 협의를 원합니다."
                  }
                ]}
                """;
        when(geminiClient.analyzeClauses(MASKED_TEXT)).thenReturn(responseWithText(json));

        ContractAnalysisAnalyzeResponse response = analyze(MASKED_TEXT, true);

        assertEquals("확인이 필요한 조항 2개가 있습니다", response.summary());
        assertEquals(2, response.clauses().size());
        assertEquals("AI가 생성한 결과입니다.", response.aiGeneratedNotice());
        assertTrue(response.disclaimer().contains("법적 효력이 없는"));
    }

    @Test
    void analyzeSummaryCountsOnlyTrueRiskFlagsNotAllClauses() {
        String json = """
                {"clauses": [
                  {
                    "originalText": "제3조 임차인은 계약 기간 중 임대인의 동의 없이 반려동물을 키울 수 없다.",
                    "riskFlag": true,
                    "explanation": "설명1",
                    "question": "질문1",
                    "suggestedText": "제안1"
                  },
                  {
                    "originalText": "제5조 보증금은 계약 종료 후 즉시 반환하지 않고 다음 세입자 입주 시 반환한다.",
                    "riskFlag": false,
                    "explanation": "설명2",
                    "question": "질문2",
                    "suggestedText": "제안2"
                  }
                ]}
                """;
        when(geminiClient.analyzeClauses(MASKED_TEXT)).thenReturn(responseWithText(json));

        ContractAnalysisAnalyzeResponse response = analyze(MASKED_TEXT, true);

        assertEquals("확인이 필요한 조항 1개가 있습니다", response.summary());
        assertEquals(2, response.clauses().size());
    }

    @Test
    void analyzeSucceedsWithZeroRiskClauses() {
        String json = clauseJson(
                "제3조 임차인은 계약 기간 중 임대인의 동의 없이 반려동물을 키울 수 없다.",
                false, "설명", "질문", "제안"
        );
        when(geminiClient.analyzeClauses(MASKED_TEXT)).thenReturn(responseWithText(json));

        ContractAnalysisAnalyzeResponse response = analyze(MASKED_TEXT, true);

        assertEquals("확인이 필요한 조항 0개가 있습니다", response.summary());
    }

    // ---------- 계약 조항으로 보이지 않는 입력 ----------

    @Test
    void analyzeReturnsAiGuidanceSummaryWhenClausesEmpty() {
        String notRelatedText = "오늘 점심 뭐 먹지 고민되네요";
        String json = """
                {"summary": "입력하신 내용이 계약 조항으로 보이지 않습니다. 특약사항이 포함된 계약 문구를 입력해주세요", "clauses": []}
                """;
        when(geminiClient.analyzeClauses(notRelatedText)).thenReturn(responseWithText(json));

        ContractAnalysisAnalyzeResponse response = analyze(notRelatedText, true);

        assertEquals(
                "입력하신 내용이 계약 조항으로 보이지 않습니다. 특약사항이 포함된 계약 문구를 입력해주세요",
                response.summary()
        );
        assertEquals(0, response.clauses().size());
    }

    @Test
    void analyzeFallsBackToCountBasedSummaryWhenClausesEmptyAndAiSummaryBlank() {
        // AI가 summary 규칙을 지키지 않고 빈 값을 준 경우까지도, 예전처럼 개수 기반
        // summary로 안전하게 대체되어야 한다(빈 안내 문구가 그대로 노출되면 안 됨).
        String json = """
                {"clauses": []}
                """;
        when(geminiClient.analyzeClauses(MASKED_TEXT)).thenReturn(responseWithText(json));

        ContractAnalysisAnalyzeResponse response = analyze(MASKED_TEXT, true);

        assertEquals("확인이 필요한 조항 0개가 있습니다", response.summary());
    }

    @Test
    void analyzeFallsBackToCountBasedSummaryWhenAiSummaryContainsMarkdown() {
        // rule 7(마크다운 금지)을 AI가 어긴 경우, 원문 대조가 불가능한 summary는 마크다운
        // 여부만 서버가 직접 걸러내고 안전한 기본 문구로 대체해야 한다.
        String notRelatedText = "오늘 점심 뭐 먹지 고민되네요";
        String json = """
                {"summary": "**입력하신 내용이 계약 조항으로 보이지 않습니다.**", "clauses": []}
                """;
        when(geminiClient.analyzeClauses(notRelatedText)).thenReturn(responseWithText(json));

        ContractAnalysisAnalyzeResponse response = analyze(notRelatedText, true);

        assertEquals("확인이 필요한 조항 0개가 있습니다", response.summary());
    }
}
