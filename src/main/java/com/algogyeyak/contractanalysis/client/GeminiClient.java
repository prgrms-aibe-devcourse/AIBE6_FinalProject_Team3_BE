package com.algogyeyak.contractanalysis.client;

import com.algogyeyak.contractanalysis.client.dto.GeminiGenerateContentResponse;

/**
 * Google Gemini(gemini-2.5-flash) generateContent API 연동 클라이언트.
 */
public interface GeminiClient {

    /**
     * @param maskedText 마스킹이 완료된 계약 조항 텍스트
     */
    GeminiGenerateContentResponse analyzeClauses(String maskedText);
}
