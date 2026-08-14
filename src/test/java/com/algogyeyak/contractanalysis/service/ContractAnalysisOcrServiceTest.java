package com.algogyeyak.contractanalysis.service;

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
    private final ContractAnalysisOcrService service =
            new ContractAnalysisOcrService(clovaOcrClient, new ImagePreprocessor());

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
    void recognizeSucceedsAndReportsUncertainFieldsWhenSomeFieldsBelowThreshold() {
        // 나머지 필드 신뢰도가 높더라도, 필드 하나(0.65)가 0.7 미만이라는 이유로 더 이상 전체를 실패시키지 않는다.
        // 대신 낮은 신뢰도 필드를 uncertainFields로 참고 정보만 내려준다.
        when(clovaOcrClient.recognize(any(), anyString()))
                .thenReturn(responseWithConfidences(0.99, 0.65, 0.98));

        ContractAnalysisOcrResponse response = service.recognize(jpegImage());

        assertEquals(1, response.uncertainFields().size());
        assertEquals(1, response.uncertainFields().get(0).index());
        assertEquals("단어", response.uncertainFields().get(0).text());
    }

    @Test
    void recognizeTreatsNullFieldConfidenceAsUncertain() {
        // Clova가 특정 필드에 confidence를 안 내려주는 경우 0.0으로 취급해 uncertainFields에 담아야 한다.
        List<ClovaOcrResponse.Field> fields = List.of(
                new ClovaOcrResponse.Field("단어", null, false)
        );
        when(clovaOcrClient.recognize(any(), anyString()))
                .thenReturn(new ClovaOcrResponse(List.of(new ClovaOcrResponse.Image("SUCCESS", fields))));

        ContractAnalysisOcrResponse response = service.recognize(jpegImage());

        assertEquals(1, response.uncertainFields().size());
        assertEquals(0, response.uncertainFields().get(0).index());
    }

    @Test
    void recognizeReportsNoUncertainFieldsWhenConfidenceIsExactlyAtThreshold() {
        // 0.7 "미만"만 불확실로 표시해야 하므로, 정확히 0.7인 경우는 uncertainFields에 포함되지 않는다.
        when(clovaOcrClient.recognize(any(), anyString()))
                .thenReturn(responseWithConfidences(0.7, 0.7));

        ContractAnalysisOcrResponse response = service.recognize(jpegImage());

        assertEquals(0, response.uncertainFields().size());
    }

    @Test
    void recognizeReturnsAverageConfidenceOnSuccess() {
        when(clovaOcrClient.recognize(any(), anyString()))
                .thenReturn(responseWithConfidences(0.8, 1.0));

        ContractAnalysisOcrResponse response = service.recognize(jpegImage());

        assertEquals(0.9, response.confidence());
        assertEquals(true, response.editable());
        assertEquals(0, response.uncertainFields().size());
    }

    @Test
    void recognizeSetsShortTextWarningWhenExtractedTextIsShort() {
        // 0자(EMPTY_RESULT)는 아니지만 계약서 내용이라기엔 너무 짧은 경우 - 거부하지 않고 힌트만 내려준다.
        when(clovaOcrClient.recognize(any(), anyString()))
                .thenReturn(responseWithConfidences(0.99, 0.98));

        ContractAnalysisOcrResponse response = service.recognize(jpegImage());

        assertEquals(true, response.shortTextWarning());
    }

    @Test
    void recognizeDoesNotSetShortTextWarningWhenExtractedTextIsLongEnough() {
        when(clovaOcrClient.recognize(any(), anyString()))
                .thenReturn(responseWithConfidences(0.99, 0.99, 0.99, 0.99, 0.99, 0.99, 0.99));

        ContractAnalysisOcrResponse response = service.recognize(jpegImage());

        assertEquals(false, response.shortTextWarning());
    }

    @Test
    void recognizeThrowsEmptyResultWhenNoFieldsRecognized() {
        when(clovaOcrClient.recognize(any(), anyString()))
                .thenReturn(new ClovaOcrResponse(List.of(new ClovaOcrResponse.Image("SUCCESS", List.of()))));

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.recognize(jpegImage())
        );

        assertEquals(ErrorCode.CONTRACT_ANALYSIS_OCR_EMPTY_RESULT, exception.getErrorCode());
    }

    @Test
    void recognizeThrowsUnsupportedFileTypeInsteadOfNpeWhenContentTypeMissing() {
        // multipart 파트에 Content-Type 헤더가 없으면 getContentType()이 null을 반환할 수 있다.
        // Map.of(...).get(null)은 NPE를 던지므로, 이 케이스는 500이 아니라 400으로 처리돼야 한다.
        MultipartFile imageWithoutContentType = mock(MultipartFile.class);
        when(imageWithoutContentType.isEmpty()).thenReturn(false);
        when(imageWithoutContentType.getContentType()).thenReturn(null);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.recognize(imageWithoutContentType)
        );

        assertEquals(ErrorCode.CONTRACT_ANALYSIS_UNSUPPORTED_FILE_TYPE, exception.getErrorCode());
    }
}
