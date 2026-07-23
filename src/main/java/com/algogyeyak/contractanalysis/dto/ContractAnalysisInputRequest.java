package com.algogyeyak.contractanalysis.dto;

import com.algogyeyak.contractanalysis.entity.InputType;
import org.springframework.web.multipart.MultipartFile;

public record ContractAnalysisInputRequest(
        InputType inputType,
        String text,
        Long propertyId,
        MultipartFile image
) {
}
