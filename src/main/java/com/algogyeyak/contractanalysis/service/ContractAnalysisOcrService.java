package com.algogyeyak.contractanalysis.service;

import com.algogyeyak.contractanalysis.client.ClovaOcrClient;
import com.algogyeyak.contractanalysis.client.dto.ClovaOcrResponse;
import com.algogyeyak.contractanalysis.dto.ContractAnalysisOcrResponse;
import com.algogyeyak.global.error.ErrorCode;
import com.algogyeyak.global.exception.BusinessException;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class ContractAnalysisOcrService {

    private static final String OCR_LOW_CONFIDENCE_FALLBACK = "MANUAL_INPUT";
    private static final double MIN_CONFIDENCE = 0.7;
    private static final long MAX_IMAGE_SIZE_BYTES = 10 * 1024 * 1024;
    private static final Map<String, String> CONTENT_TYPE_TO_CLOVA_FORMAT = Map.of(
            "image/jpeg", "jpg",
            "image/png", "png"
    );

    private final ClovaOcrClient clovaOcrClient;

    public ContractAnalysisOcrService(ClovaOcrClient clovaOcrClient) {
        this.clovaOcrClient = clovaOcrClient;
    }

    public ContractAnalysisOcrResponse recognize(MultipartFile image) {
        String format = validateAndResolveFormat(image);

        ClovaOcrResponse response = clovaOcrClient.recognize(image, format);
        List<ClovaOcrResponse.Field> fields = extractFields(response);

        if (fields.isEmpty()) {
            throw new BusinessException(ErrorCode.CONTRACT_ANALYSIS_OCR_EMPTY_RESULT);
        }

        double minConfidence = fields.stream()
                .mapToDouble(this::confidenceOf)
                .min()
                .orElse(0);

        if (minConfidence < MIN_CONFIDENCE) {
            throw new BusinessException(
                    ErrorCode.CONTRACT_ANALYSIS_OCR_LOW_CONFIDENCE,
                    ErrorCode.CONTRACT_ANALYSIS_OCR_LOW_CONFIDENCE.getMessage(),
                    OCR_LOW_CONFIDENCE_FALLBACK
            );
        }

        String extractedText = joinFields(fields);
        double averageConfidence = fields.stream()
                .mapToDouble(this::confidenceOf)
                .average()
                .orElse(0);

        return ContractAnalysisOcrResponse.of(extractedText, roundToTwoDecimals(averageConfidence));
    }

    private String validateAndResolveFormat(MultipartFile image) {
        if (image == null || image.isEmpty()) {
            throw new BusinessException(ErrorCode.CONTRACT_ANALYSIS_INVALID_INPUT);
        }

        String format = CONTENT_TYPE_TO_CLOVA_FORMAT.get(image.getContentType());
        if (format == null) {
            throw new BusinessException(ErrorCode.CONTRACT_ANALYSIS_UNSUPPORTED_FILE_TYPE);
        }

        if (image.getSize() > MAX_IMAGE_SIZE_BYTES) {
            throw new BusinessException(ErrorCode.CONTRACT_ANALYSIS_FILE_TOO_LARGE);
        }

        return format;
    }

    private List<ClovaOcrResponse.Field> extractFields(ClovaOcrResponse response) {
        if (response == null || response.images() == null || response.images().isEmpty()) {
            return List.of();
        }

        ClovaOcrResponse.Image image = response.images().get(0);
        if (!"SUCCESS".equals(image.inferResult()) || image.fields() == null) {
            return List.of();
        }

        return image.fields();
    }

    // Clova는 어절 단위로 fields를 내려주고, 각 필드의 lineBreak로 줄바꿈 여부를 표시한다.
    private String joinFields(List<ClovaOcrResponse.Field> fields) {
        StringBuilder sb = new StringBuilder();
        for (ClovaOcrResponse.Field field : fields) {
            sb.append(field.inferText());
            sb.append(Boolean.TRUE.equals(field.lineBreak()) ? "\n" : " ");
        }
        return sb.toString().trim();
    }

    private double confidenceOf(ClovaOcrResponse.Field field) {
        return field.inferConfidence() != null ? field.inferConfidence() : 0.0;
    }

    private double roundToTwoDecimals(double value) {
        return Math.round(value * 100) / 100.0;
    }
}
