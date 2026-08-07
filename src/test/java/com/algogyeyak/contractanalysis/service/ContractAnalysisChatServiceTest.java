package com.algogyeyak.contractanalysis.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.algogyeyak.contractanalysis.client.GeminiClient;
import com.algogyeyak.contractanalysis.client.dto.GeminiGenerateContentResponse;
import com.algogyeyak.contractanalysis.dto.ContractAnalysisChatClause;
import com.algogyeyak.contractanalysis.dto.ContractAnalysisChatMessage;
import com.algogyeyak.contractanalysis.dto.ContractAnalysisChatRequest;
import com.algogyeyak.contractanalysis.dto.ContractAnalysisChatResponse;
import com.algogyeyak.global.error.ErrorCode;
import com.algogyeyak.global.exception.BusinessException;
import java.util.List;
import org.junit.jupiter.api.Test;

class ContractAnalysisChatServiceTest {

    private static final ContractAnalysisChatClause CLAUSE = new ContractAnalysisChatClause(
            "제3조 임차인은 계약 기간 중 임대인의 동의 없이 반려동물을 키울 수 없다.",
            true,
            "반려동물 금지 조항입니다."
    );

    private final GeminiClient geminiClient = mock(GeminiClient.class);
    private final ContractAnalysisChatService service = new ContractAnalysisChatService(geminiClient);

    private ContractAnalysisChatResponse chat(ContractAnalysisChatClause clause, String question, List<ContractAnalysisChatMessage> history) {
        return service.chat(new ContractAnalysisChatRequest(clause, question, history));
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

    // ---------- 입력 검증 ----------

    @Test
    void chatThrowsQuestionRequiredWhenQuestionIsBlank() {
        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> chat(CLAUSE, "   ", null)
        );

        assertEquals(ErrorCode.CONTRACT_ANALYSIS_QUESTION_REQUIRED, exception.getErrorCode());
    }

    @Test
    void chatThrowsQuestionRequiredWhenQuestionIsNull() {
        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> chat(CLAUSE, null, null)
        );

        assertEquals(ErrorCode.CONTRACT_ANALYSIS_QUESTION_REQUIRED, exception.getErrorCode());
    }

    @Test
    void chatChecksQuestionBeforeClauseSoBothMissingReportsQuestionRequired() {
        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> chat(null, "", null)
        );

        assertEquals(ErrorCode.CONTRACT_ANALYSIS_QUESTION_REQUIRED, exception.getErrorCode());
    }

    @Test
    void chatThrowsInvalidInputWhenClauseIsNull() {
        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> chat(null, "반려동물 예외는 없나요?", null)
        );

        assertEquals(ErrorCode.CONTRACT_ANALYSIS_INVALID_INPUT, exception.getErrorCode());
    }

    @Test
    void chatThrowsInvalidInputWhenClauseOriginalTextIsBlank() {
        ContractAnalysisChatClause blankClause = new ContractAnalysisChatClause("  ", true, "설명");

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> chat(blankClause, "반려동물 예외는 없나요?", null)
        );

        assertEquals(ErrorCode.CONTRACT_ANALYSIS_INVALID_INPUT, exception.getErrorCode());
    }

    // ---------- AI 응답 검증 ----------

    @Test
    void chatThrowsAiResponseInvalidWhenCandidatesEmpty() {
        when(geminiClient.chat(any(), anyString(), anyList()))
                .thenReturn(new GeminiGenerateContentResponse(List.of()));

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> chat(CLAUSE, "반려동물 예외는 없나요?", null)
        );

        assertEquals(ErrorCode.CONTRACT_ANALYSIS_AI_RESPONSE_INVALID, exception.getErrorCode());
    }

    @Test
    void chatThrowsAiResponseInvalidWhenResponseTextIsBlank() {
        when(geminiClient.chat(any(), anyString(), anyList()))
                .thenReturn(responseWithText("   "));

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> chat(CLAUSE, "반려동물 예외는 없나요?", null)
        );

        assertEquals(ErrorCode.CONTRACT_ANALYSIS_AI_RESPONSE_INVALID, exception.getErrorCode());
    }

    // ---------- 성공 케이스 ----------

    @Test
    void chatSucceedsWithNullHistory() {
        when(geminiClient.chat(CLAUSE, "반려동물 예외는 없나요?", List.of()))
                .thenReturn(responseWithText("소형 반려동물은 예외로 허용되는 경우가 있으니 임대인에게 확인해보세요."));

        ContractAnalysisChatResponse response = chat(CLAUSE, "반려동물 예외는 없나요?", null);

        assertEquals("소형 반려동물은 예외로 허용되는 경우가 있으니 임대인에게 확인해보세요.", response.answer());
        assertEquals("AI가 생성한 결과입니다.", response.aiGeneratedNotice());
        assertTrue(response.disclaimer().contains("법적 효력이 없는"));
    }

    @Test
    void chatSucceedsAndPassesHistoryThroughToGeminiClient() {
        List<ContractAnalysisChatMessage> history = List.of(
                new ContractAnalysisChatMessage("user", "이 조항이 왜 위험한가요?"),
                new ContractAnalysisChatMessage("assistant", "임대인 동의 없이는 반려동물을 키울 수 없기 때문입니다.")
        );
        when(geminiClient.chat(CLAUSE, "그럼 몰래 키우면 어떻게 되나요?", history))
                .thenReturn(responseWithText("계약 위반으로 계약 해지 사유가 될 수 있습니다."));

        ContractAnalysisChatResponse response = chat(CLAUSE, "그럼 몰래 키우면 어떻게 되나요?", history);

        assertEquals("계약 위반으로 계약 해지 사유가 될 수 있습니다.", response.answer());
    }
}
