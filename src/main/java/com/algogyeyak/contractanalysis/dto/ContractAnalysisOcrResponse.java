package com.algogyeyak.contractanalysis.dto;

import java.util.List;

public record ContractAnalysisOcrResponse(
        String extractedText,
        double confidence,
        boolean editable,
        List<UncertainField> uncertainFields,
        boolean shortTextWarning
) {
    // 완전히 못 읽은 것(0자, CONTRACT_ANALYSIS_OCR_EMPTY_RESULT로 거부)과는 별개로,
    // 뭔가 읽히긴 했지만 계약서 내용이라기엔 너무 짧은 경우를 프론트에 힌트로만 알려준다 -
    // 이 자체로는 요청을 거부할 근거가 못 된다(짧은 특약 한 줄만 찍은 정당한 케이스도 있음).
    private static final int SHORT_TEXT_THRESHOLD = 20;

    public static ContractAnalysisOcrResponse of(
            String extractedText,
            double confidence,
            List<UncertainField> uncertainFields
    ) {
        return new ContractAnalysisOcrResponse(
                extractedText, confidence, true, uncertainFields, extractedText.length() < SHORT_TEXT_THRESHOLD);
    }

    public record UncertainField(String text, int index) {
    }
}
