package com.algogyeyak.contractanalysis.dto;

import java.util.List;
import org.springframework.util.StringUtils;

public record ContractAnalysisAnalyzeResponse(
        String summary,
        List<ContractAnalysisClause> clauses,
        String aiGeneratedNotice,
        String disclaimer
) {
    private static final String AI_GENERATED_NOTICE = "AI가 생성한 결과입니다.";
    private static final String DISCLAIMER =
            "법적 효력이 없는 참고 정보이며, 수정 요청 문구는 법률적 정답이 아닌 협의용 예시입니다.";

    // clauses가 비어 있으면서 AI가 summary를 함께 채워준 경우는, 입력이 계약 조항으로
    // 보이지 않아 AI가 안내 문구를 내려준 것이므로 이를 그대로 사용자에게 보여준다.
    // 그 외의 경우(조항이 하나라도 있음)는 항상 위험 조항 개수 기반 summary로 계산한다.
    public static ContractAnalysisAnalyzeResponse of(List<ContractAnalysisClause> clauses, String aiSummary) {
        if (clauses.isEmpty() && StringUtils.hasText(aiSummary)) {
            return new ContractAnalysisAnalyzeResponse(aiSummary, clauses, AI_GENERATED_NOTICE, DISCLAIMER);
        }

        long riskCount = clauses.stream()
                .filter(clause -> Boolean.TRUE.equals(clause.riskFlag()))
                .count();
        String summary = "확인이 필요한 조항 " + riskCount + "개가 있습니다";

        return new ContractAnalysisAnalyzeResponse(summary, clauses, AI_GENERATED_NOTICE, DISCLAIMER);
    }
}
