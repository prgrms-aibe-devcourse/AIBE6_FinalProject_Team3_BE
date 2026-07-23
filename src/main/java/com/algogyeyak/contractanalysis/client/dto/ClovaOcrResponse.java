package com.algogyeyak.contractanalysis.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * Clova OCR General API 응답 매핑용 DTO. 실제 사용하는 필드만 매핑한다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ClovaOcrResponse(
        List<Image> images
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Image(
            String inferResult,
            List<Field> fields
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Field(
            String inferText,
            Double inferConfidence,
            Boolean lineBreak
    ) {
    }
}
