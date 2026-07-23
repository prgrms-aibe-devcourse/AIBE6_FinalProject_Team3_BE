package com.algogyeyak.contractanalysis.service;

import com.algogyeyak.contractanalysis.dto.ContractAnalysisInputRequest;
import com.algogyeyak.contractanalysis.dto.ContractAnalysisInputResponse;
import com.algogyeyak.contractanalysis.entity.InputType;
import com.algogyeyak.contractanalysis.entity.NextStep;
import com.algogyeyak.global.error.ErrorCode;
import com.algogyeyak.global.exception.BusinessException;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

@Service
public class ContractAnalysisInputService {

    private static final Set<String> ALLOWED_IMAGE_CONTENT_TYPES = Set.of("image/jpeg", "image/png");
    private static final long MAX_IMAGE_SIZE_BYTES = 10 * 1024 * 1024;
    private static final int MIN_TEXT_LENGTH = 20;

    public ContractAnalysisInputResponse createInput(ContractAnalysisInputRequest request, Long currentUserId) {
        validateInputExists(request);

        if (request.inputType() == InputType.IMAGE) {
            validateImage(request.image());
        } else {
            validateTextLength(request.text());
        }

        validatePropertyOwnership(request.propertyId(), currentUserId);

        NextStep nextStep = request.inputType() == InputType.IMAGE ? NextStep.OCR : NextStep.MASKING;
        return ContractAnalysisInputResponse.of(request.inputType(), nextStep);
    }

    private void validateInputExists(ContractAnalysisInputRequest request) {
        boolean missing = switch (request.inputType()) {
            case IMAGE -> request.image() == null || request.image().isEmpty();
            case TEXT -> !StringUtils.hasText(request.text());
        };

        if (missing) {
            throw new BusinessException(ErrorCode.CONTRACT_ANALYSIS_INVALID_INPUT);
        }
    }

    private void validateImage(MultipartFile image) {
        if (!ALLOWED_IMAGE_CONTENT_TYPES.contains(image.getContentType())) {
            throw new BusinessException(ErrorCode.CONTRACT_ANALYSIS_UNSUPPORTED_FILE_TYPE);
        }

        if (image.getSize() > MAX_IMAGE_SIZE_BYTES) {
            throw new BusinessException(ErrorCode.CONTRACT_ANALYSIS_FILE_TOO_LARGE);
        }
    }

    private void validateTextLength(String text) {
        if (text.trim().length() < MIN_TEXT_LENGTH) {
            throw new BusinessException(ErrorCode.CONTRACT_ANALYSIS_TEXT_TOO_SHORT);
        }
    }

    private void validatePropertyOwnership(Long propertyId, Long currentUserId) {
        if (propertyId == null) {
            return;
        }

        // TODO: Property 엔티티/레포지토리 도입 후 propertyId 소유자와 currentUserId 비교하여
        // 불일치 시 BusinessException(ErrorCode.CONTRACT_ANALYSIS_FORBIDDEN) throw
    }
}
