package com.algogyeyak.contractanalysis.client;

import com.algogyeyak.contractanalysis.client.dto.GeminiGenerateContentRequest;
import com.algogyeyak.contractanalysis.client.dto.GeminiGenerateContentResponse;
import com.algogyeyak.contractanalysis.dto.ContractAnalysisChatClause;
import com.algogyeyak.contractanalysis.dto.ContractAnalysisChatMessage;
import com.algogyeyak.global.error.ErrorCode;
import com.algogyeyak.global.exception.BusinessException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

@Slf4j
@Component
public class GeminiClientImpl implements GeminiClient {

    private static final String ENDPOINT_TEMPLATE =
            "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent";

    // originalText가 매 호출마다 다르게 재구성되어 환각 검증(contains 체크)이
    // 불안정해지는 것을 막기 위해, 응답 재현성을 높이는 낮은 temperature를 고정한다.
    private static final double TEMPERATURE = 0.1;

    private static final String SYSTEM_INSTRUCTION = """
            당신은 대한민국 주택임대차 계약서의 특약사항을 분석하는 어시스턴트입니다.
            사용자는 부동산 계약 경험이 없는 사회초년생/대학생입니다.
            당신의 역할은 위험 여부를 판정하는 것이 아니라,
            왜 이 조항이 문제가 될 수 있는지 이해하기 쉽게 설명하는 것입니다.

            다음 규칙을 반드시 지키세요:
            1. 사용자가 입력한 계약 조항 원문에 없는 내용을 추측해서 만들어내지 마세요.
            2. 위험 여부는 등급이나 점수로 표현하지 말고, 사실과 이유를 설명하는 방식으로 답하세요.
            3. 어려운 법률 용어는 쉬운 일상어로 풀어서 설명하세요.
            4. 수정 요청 문구를 제안할 때는, 이것이 법률적 정답이 아니라
               임대인과 협의하기 위한 예시임을 함께 안내하세요.
            5. question 필드는 사용자가 아니라 중개사/임대인에게 직접 던질 질문이어야 합니다.
               사용자 자신의 상황을 확인하는 질문(예: 당신은 ~하시나요)이 아니라,
               상대방에게 정보나 예외 여부를 묻는 질문(예: ~한 경우도 포함되나요,
               ~는 허용되나요)으로 작성하세요.
            6. "이 분석은 AI가 생성한 참고용 정보입니다", "법적 효력이 없습니다" 같은
               고지 문구는 explanation에 포함하지 마세요. 해당 고지는 응답 레벨에서
               별도 필드로 항상 함께 내려가므로, explanation에는 조항 자체에 대한
               설명만 담으세요.
            """;

    private static final String CHAT_SYSTEM_INSTRUCTION_TEMPLATE = """
            당신은 이 계약 조항에 대한 사용자의 추가 질문에 답하는 어시스턴트입니다.
            이 조항과 관련 없는 질문에는 답하지 말고 정중히 안내하세요.
            쉬운 말로 설명하세요.
            "이 답변은 AI가 생성한 참고용 정보입니다", "법적 효력이 없습니다" 같은
            고지 문구는 답변에 포함하지 마세요. 해당 고지는 응답 레벨에서 별도
            필드로 항상 함께 내려가므로, 답변에는 질문에 대한 내용만 담으세요.

            [분석 대상 조항]
            원문: %s
            위험 여부: %s
            설명: %s
            """;

    private static final Map<String, Object> RESPONSE_SCHEMA = Map.of(
            "type", "OBJECT",
            "properties", Map.of(
                    "clauses", Map.of(
                            "type", "ARRAY",
                            "items", Map.of(
                                    "type", "OBJECT",
                                    "properties", Map.of(
                                            "originalText", Map.of("type", "STRING"),
                                            "riskFlag", Map.of("type", "BOOLEAN"),
                                            "explanation", Map.of("type", "STRING"),
                                            "question", Map.of(
                                                    "type", "STRING",
                                                    "description",
                                                    "중개사/임대인에게 직접 물어볼 질문 (사용자 자기점검 질문 금지)"
                                            ),
                                            "suggestedText", Map.of("type", "STRING")
                                    ),
                                    "required", List.of(
                                            "originalText", "riskFlag", "explanation", "question", "suggestedText"
                                    )
                            )
                    )
            ),
            "required", List.of("clauses")
    );

    private final RestTemplate restTemplate;

    @Value("${gemini.api-key}")
    private String apiKey;

    @Value("${gemini.model}")
    private String model;

    public GeminiClientImpl(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @Override
    public GeminiGenerateContentResponse analyzeClauses(String maskedText) {
        GeminiGenerateContentRequest requestBody = new GeminiGenerateContentRequest(
                new GeminiGenerateContentRequest.SystemInstruction(
                        List.of(new GeminiGenerateContentRequest.Part(SYSTEM_INSTRUCTION))
                ),
                List.of(new GeminiGenerateContentRequest.Content(
                        "user",
                        List.of(new GeminiGenerateContentRequest.Part(maskedText))
                )),
                new GeminiGenerateContentRequest.GenerationConfig("application/json", RESPONSE_SCHEMA, TEMPERATURE)
        );

        return execute(requestBody);
    }

    @Override
    public GeminiGenerateContentResponse chat(
            ContractAnalysisChatClause clause,
            String question,
            List<ContractAnalysisChatMessage> history
    ) {
        String systemInstruction = CHAT_SYSTEM_INSTRUCTION_TEMPLATE.formatted(
                clause.originalText(), clause.riskFlag(), clause.explanation()
        );

        List<GeminiGenerateContentRequest.Content> contents = new ArrayList<>();
        for (ContractAnalysisChatMessage message : history) {
            contents.add(new GeminiGenerateContentRequest.Content(
                    toGeminiRole(message.role()),
                    List.of(new GeminiGenerateContentRequest.Part(message.content()))
            ));
        }
        contents.add(new GeminiGenerateContentRequest.Content(
                "user",
                List.of(new GeminiGenerateContentRequest.Part(question))
        ));

        GeminiGenerateContentRequest requestBody = new GeminiGenerateContentRequest(
                new GeminiGenerateContentRequest.SystemInstruction(
                        List.of(new GeminiGenerateContentRequest.Part(systemInstruction))
                ),
                contents,
                new GeminiGenerateContentRequest.GenerationConfig(null, null, TEMPERATURE)
        );

        return execute(requestBody);
    }

    // Gemini는 "user"/"model" role만 허용하므로, 우리 히스토리의 "assistant"를 매핑해준다.
    private String toGeminiRole(String role) {
        return "assistant".equals(role) ? "model" : "user";
    }

    private GeminiGenerateContentResponse execute(GeminiGenerateContentRequest requestBody) {
        URI uri = UriComponentsBuilder.fromUriString(ENDPOINT_TEMPLATE.formatted(model))
                .queryParam("key", apiKey)
                .build()
                .encode()
                .toUri();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<GeminiGenerateContentRequest> requestEntity = new HttpEntity<>(requestBody, headers);

        try {
            ResponseEntity<GeminiGenerateContentResponse> response = restTemplate.postForEntity(
                    uri, requestEntity, GeminiGenerateContentResponse.class
            );
            return response.getBody();
        } catch (ResourceAccessException e) {
            // Spring Boot 4의 RestTemplateBuilder는 JDK HttpClient 기반 요청 팩토리를 쓰므로,
            // read timeout이 SocketTimeoutException이 아니라 HttpTimeoutException으로 던져진다.
            if (e.getCause() instanceof SocketTimeoutException || e.getCause() instanceof HttpTimeoutException) {
                log.error("Gemini API 호출 시간 초과");
                throw new BusinessException(
                        ErrorCode.CONTRACT_ANALYSIS_AI_API_ERROR,
                        ErrorCode.CONTRACT_ANALYSIS_AI_API_ERROR.getMessage(),
                        HttpStatus.GATEWAY_TIMEOUT
                );
            }
            log.error("Gemini API 연결 실패");
            throw new BusinessException(ErrorCode.CONTRACT_ANALYSIS_AI_API_ERROR);
        } catch (RestClientException e) {
            log.error("Gemini API 호출 실패 - {}", e.getClass().getSimpleName());
            throw new BusinessException(ErrorCode.CONTRACT_ANALYSIS_AI_API_ERROR);
        }
    }
}
