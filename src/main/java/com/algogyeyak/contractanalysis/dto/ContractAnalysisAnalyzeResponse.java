package com.algogyeyak.contractanalysis.dto;

import java.util.List;

public record ContractAnalysisAnalyzeResponse(
        String summary,
        List<ContractAnalysisClause> clauses,
        String aiGeneratedNotice,
        String disclaimer
) {
    private static final String AI_GENERATED_NOTICE = "AI가 생성한 결과입니다.";
    private static final String DISCLAIMER =
            "법적 효력이 없는 참고 정보이며, 수정 요청 문구는 법률적 정답이 아닌 협의용 예시입니다.";

    public static ContractAnalysisAnalyzeResponse of(List<ContractAnalysisClause> clauses) {
        long riskCount = clauses.stream()
                .filter(clause -> Boolean.TRUE.equals(clause.riskFlag()))
                .count();
        String summary = "확인이 필요한 조항 " + riskCount + "개가 있습니다";

        return new ContractAnalysisAnalyzeResponse(summary, clauses, AI_GENERATED_NOTICE, DISCLAIMER);
    }
}
