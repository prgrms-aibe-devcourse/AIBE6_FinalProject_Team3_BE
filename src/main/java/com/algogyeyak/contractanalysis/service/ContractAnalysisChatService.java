package com.algogyeyak.contractanalysis.service;

import com.algogyeyak.contractanalysis.client.GeminiClient;
import com.algogyeyak.contractanalysis.client.dto.GeminiGenerateContentResponse;
import com.algogyeyak.contractanalysis.dto.ContractAnalysisChatMessage;
import com.algogyeyak.contractanalysis.dto.ContractAnalysisChatRequest;
import com.algogyeyak.contractanalysis.dto.ContractAnalysisChatResponse;
import com.algogyeyak.global.error.ErrorCode;
import com.algogyeyak.global.exception.BusinessException;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class ContractAnalysisChatService {

    private final GeminiClient geminiClient;

    public ContractAnalysisChatService(GeminiClient geminiClient) {
        this.geminiClient = geminiClient;
    }

    public ContractAnalysisChatResponse chat(ContractAnalysisChatRequest request) {
        if (!StringUtils.hasText(request.question())) {
            throw new BusinessException(ErrorCode.CONTRACT_ANALYSIS_QUESTION_REQUIRED);
        }
        if (request.clause() == null || !StringUtils.hasText(request.clause().originalText())) {
            throw new BusinessException(ErrorCode.CONTRACT_ANALYSIS_INVALID_INPUT);
        }

        List<ContractAnalysisChatMessage> history = request.history() != null ? request.history() : List.of();

        GeminiGenerateContentResponse response = geminiClient.chat(request.clause(), request.question(), history);
        String answer = extractResponseText(response);

        return ContractAnalysisChatResponse.of(answer);
    }

    private String extractResponseText(GeminiGenerateContentResponse response) {
        if (response == null || response.candidates() == null || response.candidates().isEmpty()) {
            throw new BusinessException(ErrorCode.CONTRACT_ANALYSIS_AI_RESPONSE_INVALID);
        }

        GeminiGenerateContentResponse.Content content = response.candidates().get(0).content();
        if (content == null || content.parts() == null || content.parts().isEmpty()) {
            throw new BusinessException(ErrorCode.CONTRACT_ANALYSIS_AI_RESPONSE_INVALID);
        }

        String text = content.parts().get(0).text();
        if (!StringUtils.hasText(text)) {
            throw new BusinessException(ErrorCode.CONTRACT_ANALYSIS_AI_RESPONSE_INVALID);
        }

        return text;
    }
}
