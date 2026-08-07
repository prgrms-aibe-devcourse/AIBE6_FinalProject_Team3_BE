package com.algogyeyak.contractanalysis.service;

import com.algogyeyak.contractanalysis.client.GeminiClient;
import com.algogyeyak.contractanalysis.client.dto.GeminiClauseAnalysisResult;
import com.algogyeyak.contractanalysis.client.dto.GeminiGenerateContentResponse;
import com.algogyeyak.contractanalysis.dto.ContractAnalysisAnalyzeRequest;
import com.algogyeyak.contractanalysis.dto.ContractAnalysisAnalyzeResponse;
import com.algogyeyak.contractanalysis.dto.ContractAnalysisClause;
import com.algogyeyak.global.error.ErrorCode;
import com.algogyeyak.global.exception.BusinessException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class ContractAnalysisAnalyzeService {

    private final GeminiClient geminiClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ContractAnalysisAnalyzeService(GeminiClient geminiClient) {
        this.geminiClient = geminiClient;
    }

    public ContractAnalysisAnalyzeResponse analyze(ContractAnalysisAnalyzeRequest request) {
        if (!Boolean.TRUE.equals(request.userConfirmed())) {
            throw new BusinessException(ErrorCode.CONTRACT_ANALYSIS_MASKING_NOT_CONFIRMED);
        }
        if (!StringUtils.hasText(request.maskedText())) {
            throw new BusinessException(ErrorCode.CONTRACT_ANALYSIS_INVALID_INPUT);
        }

        GeminiGenerateContentResponse response = geminiClient.analyzeClauses(request.maskedText());
        String responseText = extractResponseText(response);
        List<ContractAnalysisClause> clauses = parseAndValidateSchema(responseText);
        validateNoHallucination(clauses, request.maskedText());

        return ContractAnalysisAnalyzeResponse.of(clauses);
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

    private List<ContractAnalysisClause> parseAndValidateSchema(String responseText) {
        GeminiClauseAnalysisResult result;
        try {
            result = objectMapper.readValue(responseText, GeminiClauseAnalysisResult.class);
        } catch (JsonProcessingException e) {
            throw new BusinessException(ErrorCode.CONTRACT_ANALYSIS_AI_RESPONSE_INVALID);
        }

        if (result == null || result.clauses() == null) {
            throw new BusinessException(ErrorCode.CONTRACT_ANALYSIS_AI_RESPONSE_INVALID);
        }

        for (ContractAnalysisClause clause : result.clauses()) {
            if (!isSchemaValid(clause)) {
                throw new BusinessException(ErrorCode.CONTRACT_ANALYSIS_AI_RESPONSE_INVALID);
            }
        }

        return result.clauses();
    }

    private boolean isSchemaValid(ContractAnalysisClause clause) {
        return clause != null
                && StringUtils.hasText(clause.originalText())
                && clause.riskFlag() != null
                && StringUtils.hasText(clause.explanation())
                && StringUtils.hasText(clause.question())
                && StringUtils.hasText(clause.suggestedText());
    }

    private static final Pattern WHITESPACE_RUN = Pattern.compile("\\s+");
    // "제10조(비용의 정산)"처럼 조 제목 뒤에 AI가 ①이 아닌 다른 항을 이어붙이는 경우,
    // 원문에서는 제목 바로 뒤에 오는 항이 ①뿐이라 "제목+그 외 항" 조합은 연속된 문자열로
    // 존재하지 않는다. 이런 경우까지 환각으로 오판하지 않도록 선행 조 제목은 비교에서 제외한다.
    private static final Pattern ARTICLE_TITLE_PREFIX = Pattern.compile("^제\\d+조(\\([^)]*\\))?\\s*");
    // 체크박스형 선택지(없음/있음 등)를 AI가 대괄호로 감싸 반환하는 경우가 있어 단어 비교 전 제거한다.
    private static final Pattern BRACKET_CHARS = Pattern.compile("[\\[\\]]");
    // 표/체크박스 양식이 OCR로 평문화되며 어순이 꼬이거나 단어가 누락되는 경우, 연속 문자열
    // 매칭은 구조적으로 실패한다. 이런 조항은 원문 단어 중 이 비율 이상이(순서 무관) 마스킹
    // 텍스트에 존재하면 환각이 아닌 것으로 본다.
    private static final double WORD_MATCH_THRESHOLD = 0.85;
    // 인용문이 너무 짧으면 비율 기준이 오탐(정상 조항 탈락)·누락(허구 문구 통과) 양쪽에
    // 취약해지므로, 이 단어 수 미만이면 전체 단어가 다 존재해야만 통과시킨다.
    private static final int SHORT_QUOTE_WORD_COUNT = 5;

    // AI가 입력에 없는 조항을 지어내는 것(환각)을 막기 위해, 반환된 조항의 원문이
    // 실제로 마스킹된 입력 텍스트 안에 그대로 존재하는지 확인한다.
    // AI가 줄바꿈을 재구성하거나 공백을 다르게 반환하는 경우가 있어, 공백류를 하나로
    // 정규화한 뒤 비교한다(정상적인 조항까지 환각으로 오판하는 것을 방지).
    private void validateNoHallucination(List<ContractAnalysisClause> clauses, String maskedText) {
        String normalizedMaskedText = normalizeWhitespace(maskedText);
        Set<String> maskedWords = new HashSet<>(Arrays.asList(normalizedMaskedText.split(" ")));

        for (ContractAnalysisClause clause : clauses) {
            String normalizedOriginalText = normalizeWhitespace(clause.originalText());
            String withoutArticleTitle = ARTICLE_TITLE_PREFIX.matcher(normalizedOriginalText).replaceFirst("");
            boolean contained = normalizedMaskedText.contains(normalizedOriginalText)
                    || normalizedMaskedText.contains(withoutArticleTitle)
                    || matchesByWords(withoutArticleTitle, maskedWords);
            if (!contained) {
                throw new BusinessException(ErrorCode.CONTRACT_ANALYSIS_AI_HALLUCINATION);
            }
        }
    }

    private boolean matchesByWords(String originalText, Set<String> maskedWords) {
        String withoutBrackets = BRACKET_CHARS.matcher(originalText).replaceAll("");
        List<String> words = Arrays.stream(withoutBrackets.split(" "))
                .filter(StringUtils::hasText)
                .toList();
        if (words.isEmpty()) {
            return false;
        }

        long matchedCount = words.stream().filter(maskedWords::contains).count();
        if (words.size() < SHORT_QUOTE_WORD_COUNT) {
            return matchedCount == words.size();
        }
        return (double) matchedCount / words.size() >= WORD_MATCH_THRESHOLD;
    }

    private String normalizeWhitespace(String text) {
        return WHITESPACE_RUN.matcher(text).replaceAll(" ").trim();
    }
}
