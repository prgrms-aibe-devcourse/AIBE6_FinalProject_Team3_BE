package com.algogyeyak.contractanalysis.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.algogyeyak.contractanalysis.client.ClovaOcrClient;
import com.algogyeyak.contractanalysis.client.dto.ClovaOcrResponse;
import com.algogyeyak.contractanalysis.dto.ContractAnalysisOcrResponse;
import com.algogyeyak.global.error.ErrorCode;
import com.algogyeyak.global.exception.BusinessException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.multipart.MultipartFile;

class ContractAnalysisOcrServiceTest {

    private final ClovaOcrClient clovaOcrClient = mock(ClovaOcrClient.class);
    private final ContractAnalysisOcrService service = new ContractAnalysisOcrService(clovaOcrClient);

    private MultipartFile jpegImage() {
        MultipartFile image = mock(MultipartFile.class);
        when(image.isEmpty()).thenReturn(false);
        when(image.getContentType()).thenReturn("image/jpeg");
        when(image.getSize()).thenReturn(1024L);
        return image;
    }

    private ClovaOcrResponse responseWithConfidences(double... confidences) {
        List<ClovaOcrResponse.Field> fields = new java.util.ArrayList<>();
        for (double confidence : confidences) {
            fields.add(new ClovaOcrResponse.Field("단어", confidence, false));
        }
        return new ClovaOcrResponse(List.of(new ClovaOcrResponse.Image("SUCCESS", fields)));
    }

    @Test
    void recognizeThrowsLowConfidenceWhenAnyFieldBelowThreshold() {
        // 나머지는 신뢰도가 높아도, 필드 하나(0.65)가 0.7 미만이면 전체가 실패해야 한다.
        when(clovaOcrClient.recognize(any(), anyString()))
                .thenReturn(responseWithConfidences(0.99, 0.65, 0.98));

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.recognize(jpegImage())
        );

        assertEquals(ErrorCode.CONTRACT_ANALYSIS_OCR_LOW_CONFIDENCE, exception.getErrorCode());
        assertEquals("MANUAL_INPUT", exception.getFallback());
    }

    @Test
    void recognizeThrowsLowConfidenceWhenFieldConfidenceIsNull() {
        // Clova가 특정 필드에 confidence를 안 내려주는 경우 0.0으로 취급해 안전하게 걸러야 한다.
        List<ClovaOcrResponse.Field> fields = List.of(
                new ClovaOcrResponse.Field("단어", null, false)
        );
        when(clovaOcrClient.recognize(any(), anyString()))
                .thenReturn(new ClovaOcrResponse(List.of(new ClovaOcrResponse.Image("SUCCESS", fields))));

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.recognize(jpegImage())
        );

        assertEquals(ErrorCode.CONTRACT_ANALYSIS_OCR_LOW_CONFIDENCE, exception.getErrorCode());
    }

    @Test
    void recognizeSucceedsWhenConfidenceIsExactlyAtThreshold() {
        // 0.7 "미만"만 실패해야 하므로, 정확히 0.7인 경우는 통과해야 한다.
        when(clovaOcrClient.recognize(any(), anyString()))
                .thenReturn(responseWithConfidences(0.7, 0.7));

        assertDoesNotThrow(() -> service.recognize(jpegImage()));
    }

    @Test
    void recognizeReturnsAverageConfidenceOnSuccess() {
        when(clovaOcrClient.recognize(any(), anyString()))
                .thenReturn(responseWithConfidences(0.8, 1.0));

        ContractAnalysisOcrResponse response = service.recognize(jpegImage());

        assertEquals(0.9, response.confidence());
        assertEquals(true, response.editable());
    }
}
