package com.algogyeyak.contractanalysis.client;

import com.algogyeyak.contractanalysis.client.dto.ClovaOcrRequestMessage;
import com.algogyeyak.contractanalysis.client.dto.ClovaOcrResponse;
import com.algogyeyak.global.error.ErrorCode;
import com.algogyeyak.global.exception.BusinessException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.ConnectTimeoutException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

@Slf4j
@Component
public class ClovaOcrClientImpl implements ClovaOcrClient {

    private static final String IMAGE_NAME = "contract";

    private final RestTemplate restTemplate;

    // SecurityConfig와 동일한 이유로, 이 클래스 내부 직렬화 전용 ObjectMapper를 별도로 둔다.
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${clova.ocr.invoke-url}")
    private String invokeUrl;

    @Value("${clova.ocr.secret-key}")
    private String secretKey;

    public ClovaOcrClientImpl(@Qualifier("clovaRestTemplate") RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @Override
    public ClovaOcrResponse recognize(MultipartFile image, String format) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.set("X-OCR-SECRET", secretKey);

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("message", buildMessagePart(format));
        body.add("file", buildFilePart(image));

        HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);

        try {
            return restTemplate.postForEntity(invokeUrl, requestEntity, ClovaOcrResponse.class).getBody();
        } catch (ResourceAccessException e) {
            // clovaRestTemplate(ContractAnalysisRestTemplateConfig)는 Apache HttpClient5 기반이라,
            // 연결 후 응답 대기 중 타임아웃은 SocketTimeoutException으로, connect 단계 타임아웃은
            // 별도의 ConnectTimeoutException(SocketTimeoutException이 아님)으로 던져진다 - 둘 다
            // 사용자 입장에서는 동일한 "시간 초과"이므로 함께 잡는다.
            if (e.getCause() instanceof SocketTimeoutException || e.getCause() instanceof ConnectTimeoutException) {
                log.error("Clova OCR API 호출 시간 초과");
                throw new BusinessException(
                        ErrorCode.CONTRACT_ANALYSIS_OCR_API_ERROR,
                        ErrorCode.CONTRACT_ANALYSIS_OCR_API_ERROR.getMessage(),
                        HttpStatus.GATEWAY_TIMEOUT
                );
            }
            log.error("Clova OCR API 연결 실패");
            throw new BusinessException(ErrorCode.CONTRACT_ANALYSIS_OCR_API_ERROR);
        } catch (RestClientException e) {
            log.error("Clova OCR API 호출 실패 - {}", e.getClass().getSimpleName());
            throw new BusinessException(ErrorCode.CONTRACT_ANALYSIS_OCR_API_ERROR);
        }
    }

    private HttpEntity<String> buildMessagePart(String format) {
        ClovaOcrRequestMessage message = new ClovaOcrRequestMessage(
                "V2",
                UUID.randomUUID().toString(),
                System.currentTimeMillis(),
                "ko",
                List.of(new ClovaOcrRequestMessage.Image(format, IMAGE_NAME))
        );

        HttpHeaders partHeaders = new HttpHeaders();
        partHeaders.setContentType(MediaType.APPLICATION_JSON);
        try {
            return new HttpEntity<>(objectMapper.writeValueAsString(message), partHeaders);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Clova OCR 요청 메시지 직렬화 실패", e);
        }
    }

    private HttpEntity<ByteArrayResource> buildFilePart(MultipartFile image) {
        HttpHeaders partHeaders = new HttpHeaders();
        partHeaders.setContentType(MediaType.parseMediaType(image.getContentType()));

        try {
            ByteArrayResource resource = new ByteArrayResource(image.getBytes()) {
                @Override
                public String getFilename() {
                    return IMAGE_NAME;
                }
            };
            return new HttpEntity<>(resource, partHeaders);
        } catch (IOException e) {
            throw new IllegalStateException("업로드된 이미지를 읽을 수 없습니다.", e);
        }
    }
}
