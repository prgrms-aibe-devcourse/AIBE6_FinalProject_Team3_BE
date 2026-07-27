package com.algogyeyak.contractanalysis.controller;

import com.algogyeyak.auth.jwt.JwtUserPrincipal;
import com.algogyeyak.contractanalysis.dto.ContractAnalysisInputRequest;
import com.algogyeyak.contractanalysis.dto.ContractAnalysisInputResponse;
import com.algogyeyak.contractanalysis.dto.ContractAnalysisMaskingRequest;
import com.algogyeyak.contractanalysis.dto.ContractAnalysisMaskingResponse;
import com.algogyeyak.contractanalysis.dto.ContractAnalysisOcrResponse;
import com.algogyeyak.contractanalysis.entity.InputType;
import com.algogyeyak.contractanalysis.service.ContractAnalysisInputService;
import com.algogyeyak.contractanalysis.service.ContractAnalysisMaskingService;
import com.algogyeyak.contractanalysis.service.ContractAnalysisOcrService;
import com.algogyeyak.global.response.ApiResponse;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/contract-analysis")
public class ContractAnalysisController {

    private final ContractAnalysisInputService contractAnalysisInputService;
    private final ContractAnalysisOcrService contractAnalysisOcrService;
    private final ContractAnalysisMaskingService contractAnalysisMaskingService;

    public ContractAnalysisController(
            ContractAnalysisInputService contractAnalysisInputService,
            ContractAnalysisOcrService contractAnalysisOcrService,
            ContractAnalysisMaskingService contractAnalysisMaskingService
    ) {
        this.contractAnalysisInputService = contractAnalysisInputService;
        this.contractAnalysisOcrService = contractAnalysisOcrService;
        this.contractAnalysisMaskingService = contractAnalysisMaskingService;
    }

    @PostMapping(value = "/inputs", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<ContractAnalysisInputResponse>> createInput(
            @RequestParam InputType inputType,
            @RequestParam(required = false) String text,
            @RequestParam(required = false) Long propertyId,
            @RequestPart(required = false) MultipartFile image,
            @AuthenticationPrincipal JwtUserPrincipal principal
    ) {
        ContractAnalysisInputRequest request = new ContractAnalysisInputRequest(inputType, text, propertyId, image);
        ContractAnalysisInputResponse response = contractAnalysisInputService.createInput(request, principal.userId());
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @PostMapping(value = "/ocr", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<ContractAnalysisOcrResponse>> recognizeText(
            @RequestPart MultipartFile image
    ) {
        ContractAnalysisOcrResponse response = contractAnalysisOcrService.recognize(image);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @PostMapping("/masking")
    public ResponseEntity<ApiResponse<ContractAnalysisMaskingResponse>> mask(
            @RequestBody ContractAnalysisMaskingRequest request
    ) {
        ContractAnalysisMaskingResponse response = contractAnalysisMaskingService.mask(request);
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}
