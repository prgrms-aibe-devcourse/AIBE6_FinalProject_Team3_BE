package com.algogyeyak.contractanalysis.dto;

public record ContractAnalysisChatResponse(
        String answer,
        String aiGeneratedNotice,
        String disclaimer
) {
    private static final String AI_GENERATED_NOTICE = "AI가 생성한 결과입니다.";
    private static final String DISCLAIMER = "법적 효력이 없는 참고 정보입니다.";

    public static ContractAnalysisChatResponse of(String answer) {
        return new ContractAnalysisChatResponse(answer, AI_GENERATED_NOTICE, DISCLAIMER);
    }
}
