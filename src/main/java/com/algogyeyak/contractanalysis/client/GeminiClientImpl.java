package com.algogyeyak.contractanalysis.client;

import com.algogyeyak.contractanalysis.client.dto.GeminiGenerateContentRequest;
import com.algogyeyak.contractanalysis.client.dto.GeminiGenerateContentResponse;
import com.algogyeyak.global.error.ErrorCode;
import com.algogyeyak.global.exception.BusinessException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpTimeoutException;
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
            5. 모든 답변에는 이 분석이 법적 효력이 없는 참고용 정보이며,
               AI가 생성한 결과임을 명시하세요.
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
                                            "question", Map.of("type", "STRING"),
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
                new GeminiGenerateContentRequest.GenerationConfig("application/json", RESPONSE_SCHEMA)
        );

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
