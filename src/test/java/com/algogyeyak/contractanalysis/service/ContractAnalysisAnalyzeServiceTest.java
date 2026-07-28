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
import com.algogyeyak.global.error.ErrorCode;
import com.algogyeyak.global.exception.BusinessException;
import java.util.List;
import org.junit.jupiter.api.Test;

class ContractAnalysisAnalyzeServiceTest {

    private static final String MASKED_TEXT =
            "제3조 임차인은 계약 기간 중 임대인의 동의 없이 반려동물을 키울 수 없다. "
                    + "제5조 보증금은 계약 종료 후 즉시 반환하지 않고 다음 세입자 입주 시 반환한다.";

    private final GeminiClient geminiClient = mock(GeminiClient.class);
    private final ContractAnalysisAnalyzeService service = new ContractAnalysisAnalyzeService(geminiClient);

    private ContractAnalysisAnalyzeResponse analyze(String maskedText, Boolean userConfirmed) {
        return service.analyze(new ContractAnalysisAnalyzeRequest(maskedText, userConfirmed, null));
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
}
