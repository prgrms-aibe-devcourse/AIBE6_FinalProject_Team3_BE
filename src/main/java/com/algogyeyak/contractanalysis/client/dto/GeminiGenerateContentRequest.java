package com.algogyeyak.contractanalysis.client.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/**
 * Gemini generateContent REST 요청 바디. 필드명은 Google 공식 문서의 REST 예제를 그대로 따른다
 * (system_instruction만 snake_case, 나머지는 camelCase).
 */
public record GeminiGenerateContentRequest(
        @JsonProperty("system_instruction") SystemInstruction systemInstruction,
        List<Content> contents,
        GenerationConfig generationConfig
) {
    public record SystemInstruction(List<Part> parts) {
    }

    public record Content(String role, List<Part> parts) {
    }

    public record Part(String text) {
    }

    public record GenerationConfig(
            String responseMimeType,
            Map<String, Object> responseSchema
    ) {
    }
}
