package com.algogyeyak.contractanalysis.service;

import com.algogyeyak.contractanalysis.client.GeminiClient;
import com.algogyeyak.contractanalysis.client.dto.GeminiClauseAnalysisResult;
import com.algogyeyak.contractanalysis.client.dto.GeminiGenerateContentResponse;
import com.algogyeyak.contractanalysis.dto.ContractAnalysisAnalyzeRequest;
import com.algogyeyak.contractanalysis.dto.ContractAnalysisAnalyzeResponse;
import com.algogyeyak.contractanalysis.dto.ContractAnalysisClause;
import com.algogyeyak.contractanalysis.entity.ContractRequest;
import com.algogyeyak.contractanalysis.repository.ContractRequestRepository;
import com.algogyeyak.global.error.ErrorCode;
import com.algogyeyak.global.exception.BusinessException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Slf4j
@Service
public class ContractAnalysisAnalyzeService {

    private final GeminiClient geminiClient;
    private final ContractAnalysisMaskingService maskingService;
    private final ContractRequestRepository contractRequestRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ContractAnalysisAnalyzeService(
            GeminiClient geminiClient,
            ContractAnalysisMaskingService maskingService,
            ContractRequestRepository contractRequestRepository
    ) {
        this.geminiClient = geminiClient;
        this.maskingService = maskingService;
        this.contractRequestRepository = contractRequestRepository;
    }

    public ContractAnalysisAnalyzeResponse analyze(Long userId, ContractAnalysisAnalyzeRequest request) {
        if (!Boolean.TRUE.equals(request.userConfirmed())) {
            throw new BusinessException(ErrorCode.CONTRACT_ANALYSIS_MASKING_NOT_CONFIRMED);
        }
        if (!StringUtils.hasText(request.maskedText()) || request.inputType() == null) {
            throw new BusinessException(ErrorCode.CONTRACT_ANALYSIS_INVALID_INPUT);
        }
        // userConfirmed만으로는 클라이언트가 /masking을 실제로 거쳤는지 보장할 수 없으므로,
        // 마스킹 패턴 자체를 재사용해 주민등록번호/전화번호/계좌번호가 남아있으면 서버가 직접 거부한다.
        if (maskingService.containsUnmaskedPii(request.maskedText())) {
            throw new BusinessException(ErrorCode.CONTRACT_ANALYSIS_UNMASKED_PII_DETECTED);
        }

        GeminiGenerateContentResponse response = geminiClient.analyzeClauses(request.maskedText());
        String responseText = extractResponseText(response);
        GeminiClauseAnalysisResult result = parseAndValidateSchema(responseText);
        List<ContractAnalysisClause> verifiedClauses =
                filterHallucinatedClauses(result.clauses(), request.maskedText());

        ContractAnalysisAnalyzeResponse analyzeResponse =
                ContractAnalysisAnalyzeResponse.of(verifiedClauses, sanitizeAiSummary(result.summary()));

        ContractRequest contractRequest = ContractRequest.create(
                userId,
                request.propertyId(),
                request.inputType(),
                analyzeResponse.summary(),
                analyzeResponse.disclaimer(),
                analyzeResponse.aiGeneratedNotice(),
                analyzeResponse.clauses()
        );
        contractRequestRepository.save(contractRequest);

        return analyzeResponse;
    }

    // clauses.isEmpty()일 때만 사용되는 summary라 원문 대조(환각 검증)는 애초에 성립하지 않는다 -
    // 대신 rule 7(마크다운 금지)을 어긴 응답만 걸러내고, 걸리면 count 기반 기본 문구로 대체한다.
    private static final Pattern MARKDOWN_PATTERN = Pattern.compile("\\*\\*.+?\\*\\*|^\\s*[-*]\\s|^#{1,6}\\s", Pattern.MULTILINE);

    private String sanitizeAiSummary(String aiSummary) {
        if (!StringUtils.hasText(aiSummary) || MARKDOWN_PATTERN.matcher(aiSummary).find()) {
            return null;
        }
        return aiSummary;
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

    private GeminiClauseAnalysisResult parseAndValidateSchema(String responseText) {
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

        return result;
    }

    private boolean isSchemaValid(ContractAnalysisClause clause) {
        return clause != null
                && StringUtils.hasText(clause.title())
                && StringUtils.hasText(clause.originalText())
                && clause.riskFlag() != null
                && StringUtils.hasText(clause.explanation())
                && StringUtils.hasText(clause.question())
                && StringUtils.hasText(clause.suggestedText());
    }

    // \s는 ASCII 공백류만 잡아 U+3000(전각 공백) 등 한글 문서에 흔한 유니코드 공백을
    // 정규화하지 못하므로, 공백 분리자 카테고리(\p{Zs})와 전각 공백을 함께 포함한다.
    // 밑줄(_)은 서식 문서에서 빈칸 표시(____)로 흔히 쓰여 실질적으로 공백류이므로 함께 포함한다.
    // 따옴표(중첩 예시 문장을 감싸는 용도)는 AI가 반환할 때 보통 빠지는데, 원문에서 앞뒤 단어에
    // 공백 없이 붙어 있으면("있다"는) 그 단어가 통째로 달라져 n-gram 비교가 깨진다. 삭제가 아니라
    // 공백으로 치환해야 따옴표로 붙어 있던 단어("있다"+"는")가 원래 경계대로 다시 분리된다.
    private static final Pattern WHITESPACE_RUN = Pattern.compile("[\\s\\u3000\\p{Zs}_\"'“”‘’]+");
    // "제10조(비용의 정산)"처럼 조 제목 뒤에 AI가 ①이 아닌 다른 항을 이어붙이는 경우,
    // 원문에서는 제목 바로 뒤에 오는 항이 ①뿐이라 "제목+그 외 항" 조합은 연속된 문자열로
    // 존재하지 않는다. 이런 경우까지 환각으로 오판하지 않도록 선행 조 제목은 비교에서 제외한다.
    // "제10조의2(...)"처럼 가지번호가 붙은 조항 제목도 함께 벗겨낸다.
    private static final Pattern ARTICLE_TITLE_PREFIX = Pattern.compile("^제\\d+조(의\\d+)?(\\([^)]*\\))?\\s*");
    // 체크박스형 선택지(없음/있음 등)를 AI가 대괄호로 감싸 반환하는 경우가 있어 단어 비교 전 제거한다.
    private static final Pattern BRACKET_CHARS = Pattern.compile("[\\[\\]]");
    // 표/체크박스 양식이 OCR로 평문화되며 어순이 꼬이거나 단어가 누락되는 경우, 연속 문자열
    // 매칭은 구조적으로 실패한다. 이런 조항은 원문과 연속된 N개 단어(n-gram) 단위로 비교해
    // 이 비율 이상이 마스킹 텍스트에 그대로(연속해서) 존재하면 환각이 아닌 것으로 본다.
    // 완전히 순서를 무시한 단어 집합 비교는 문서 곳곳의 단어를 끌어모아 재조합한 허구
    // 문장까지 통과시킬 수 있어 사용하지 않는다.
    private static final int NGRAM_SIZE = 3;
    private static final double WORD_MATCH_THRESHOLD = 0.85;
    // 인용문이 너무 짧으면 비율 기준이 오탐(정상 조항 탈락)·누락(허구 문구 통과) 양쪽에
    // 취약해지므로, 이 단어 수 미만이면 전체 단어가 다 존재해야만 통과시킨다.
    private static final int SHORT_QUOTE_WORD_COUNT = 5;
    // OCR 오타 교정(예: "잉대인"→"임대인")으로 원문 단어 자체가 바뀌면, 그 오타가 조항 전체에
    // 흩어져 있을 경우 대부분의 3-단어 n-gram이 정확히 일치하지 않아 정상적인 교정까지
    // 환각으로 오판할 수 있다. 정확 일치 검증이 실패한 경우에 한해, 단어 하나당 편집거리
    // (Levenshtein) 1 이내 차이는 같은 단어로 보는 퍼지 n-gram 비교를 추가로 시도한다.
    // 연속된 n개 단어라는 순서 제약은 그대로 유지하므로, 문서 곳곳의 단어를 끌어모아
    // 재조합한 허구 문장까지 통과시키지는 않는다.
    private static final int WORD_EDIT_DISTANCE_TOLERANCE = 1;

    // AI가 입력에 없는 조항을 지어내는 것(환각)을 막기 위해, 반환된 조항의 원문이
    // 실제로 마스킹된 입력 텍스트 안에 그대로 존재하는지 확인한다.
    // AI가 줄바꿈을 재구성하거나 공백을 다르게 반환하는 경우가 있어, 공백류를 하나로
    // 정규화한 뒤 비교한다(정상적인 조항까지 환각으로 오판하는 것을 방지).
    // 검증에 실패한 조항은 (체크박스/표 양식처럼 OCR 품질이 유독 나쁜 경우 등) 조용히
    // 제외하고 나머지는 정상 반환한다 - 조항 하나의 검증 실패로 전체 분석 결과를 날리지
    // 않기 위함이다. 다만 모든 조항이 걸러져 하나도 남지 않으면 AI 응답 자체를 신뢰할 수
    // 없다고 보고 에러를 던진다.
    private List<ContractAnalysisClause> filterHallucinatedClauses(
            List<ContractAnalysisClause> clauses, String maskedText
    ) {
        if (clauses.isEmpty()) {
            return clauses;
        }

        String normalizedMaskedText = normalizeWhitespace(maskedText);
        List<String> maskedWordList = Arrays.stream(normalizedMaskedText.split(" "))
                .filter(StringUtils::hasText)
                .toList();
        Set<String> maskedWords = new HashSet<>(maskedWordList);
        Set<String> maskedNgrams = new HashSet<>(buildNgrams(maskedWordList, NGRAM_SIZE));

        List<ContractAnalysisClause> verifiedClauses = new ArrayList<>();
        for (ContractAnalysisClause clause : clauses) {
            String normalizedOriginalText = normalizeWhitespace(clause.originalText());
            String withoutArticleTitle = ARTICLE_TITLE_PREFIX.matcher(normalizedOriginalText).replaceFirst("");
            boolean contained = normalizedMaskedText.contains(normalizedOriginalText)
                    || normalizedMaskedText.contains(withoutArticleTitle)
                    || matchesByNgrams(withoutArticleTitle, maskedWords, maskedNgrams, maskedWordList);
            if (contained) {
                verifiedClauses.add(clause);
            } else {
                log.warn("환각 검증 실패로 조항 제외 - originalText=[{}]", normalizedOriginalText);
            }
        }

        if (verifiedClauses.isEmpty()) {
            throw new BusinessException(ErrorCode.CONTRACT_ANALYSIS_AI_HALLUCINATION);
        }
        return verifiedClauses;
    }

    private boolean matchesByNgrams(
            String originalText, Set<String> maskedWords, Set<String> maskedNgrams, List<String> maskedWordList
    ) {
        String withoutBrackets = BRACKET_CHARS.matcher(originalText).replaceAll("");
        List<String> words = Arrays.stream(withoutBrackets.split(" "))
                .filter(StringUtils::hasText)
                .toList();
        if (words.isEmpty()) {
            return false;
        }
        if (words.size() < SHORT_QUOTE_WORD_COUNT) {
            return words.stream().allMatch(word -> maskedWords.contains(word) || fuzzyContains(maskedWords, word));
        }

        List<String> ngrams = buildNgrams(words, NGRAM_SIZE);
        long matchedCount = ngrams.stream().filter(maskedNgrams::contains).count();
        double ratio = (double) matchedCount / ngrams.size();
        if (ratio >= WORD_MATCH_THRESHOLD) {
            return true;
        }

        double fuzzyRatio = fuzzyNgramMatchRatio(words, maskedWordList);
        return fuzzyRatio >= WORD_MATCH_THRESHOLD;
    }

    private List<String> buildNgrams(List<String> words, int n) {
        if (words.size() < n) {
            return List.of();
        }
        List<String> ngrams = new ArrayList<>();
        for (int i = 0; i <= words.size() - n; i++) {
            ngrams.add(String.join(" ", words.subList(i, i + n)));
        }
        return ngrams;
    }

    private boolean fuzzyContains(Set<String> maskedWords, String word) {
        return maskedWords.stream().anyMatch(masked -> isFuzzyWordMatch(word, masked));
    }

    // 정확 일치 n-gram 검증이 실패했을 때만 호출되는 폴백이므로, 마스킹 텍스트 전체를
    // 훑는 비용(O(단어수 * NGRAM_SIZE))을 감수한다 - 정상 OCR 품질 문서의 통상 경로에는
    // 영향이 없다.
    private double fuzzyNgramMatchRatio(List<String> words, List<String> maskedWordList) {
        int totalNgrams = words.size() - NGRAM_SIZE + 1;
        if (totalNgrams <= 0 || maskedWordList.size() < NGRAM_SIZE) {
            return 0.0;
        }
        int matched = 0;
        for (int i = 0; i <= words.size() - NGRAM_SIZE; i++) {
            if (existsFuzzyWindow(words, i, maskedWordList)) {
                matched++;
            }
        }
        return (double) matched / totalNgrams;
    }

    private boolean existsFuzzyWindow(List<String> words, int start, List<String> maskedWordList) {
        for (int j = 0; j <= maskedWordList.size() - NGRAM_SIZE; j++) {
            boolean allMatch = true;
            for (int k = 0; k < NGRAM_SIZE; k++) {
                if (!isFuzzyWordMatch(words.get(start + k), maskedWordList.get(j + k))) {
                    allMatch = false;
                    break;
                }
            }
            if (allMatch) {
                return true;
            }
        }
        return false;
    }

    private boolean isFuzzyWordMatch(String a, String b) {
        if (a.equals(b)) {
            return true;
        }
        // 1글자 단어는 편집거리 1 이내에 사실상 모든 다른 1글자 단어가 들어와 버려
        // ("수"↔"것" 모두 거리 1) 퍼지 매칭이 무의미해지므로 정확히 일치할 때만 인정한다.
        if (a.length() < 2 || b.length() < 2) {
            return false;
        }
        if (Math.abs(a.length() - b.length()) > WORD_EDIT_DISTANCE_TOLERANCE) {
            return false;
        }
        return levenshteinDistance(a, b) <= WORD_EDIT_DISTANCE_TOLERANCE;
    }

    private int levenshteinDistance(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] curr = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            curr[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev;
            prev = curr;
            curr = tmp;
        }
        return prev[b.length()];
    }

    private String normalizeWhitespace(String text) {
        return WHITESPACE_RUN.matcher(text).replaceAll(" ").trim();
    }
}
