package com.algogyeyak.contractanalysis.client;

import com.algogyeyak.contractanalysis.client.dto.GeminiGenerateContentResponse;
import com.algogyeyak.contractanalysis.dto.ContractAnalysisChatClause;
import com.algogyeyak.contractanalysis.dto.ContractAnalysisChatMessage;
import java.util.List;

/**
 * Google Gemini(gemini-2.5-flash) generateContent API 연동 클라이언트.
 */
public interface GeminiClient {

    /**
     * @param maskedText 마스킹이 완료된 계약 조항 텍스트
     */
    GeminiGenerateContentResponse analyzeClauses(String maskedText);

    /**
     * 특정 조항에 대한 사용자의 추가 질문에 답한다.
     *
     * @param clause   질의 대상 조항
     * @param question 사용자 질문
     * @param history  이전 대화 이력(최신 질문 제외)
     */
    GeminiGenerateContentResponse chat(
            ContractAnalysisChatClause clause,
            String question,
            List<ContractAnalysisChatMessage> history
    );
}
