package com.algogyeyak.global.s3.controller;

import com.algogyeyak.global.response.ApiResponse;
import com.algogyeyak.global.s3.dto.PresignedUploadRequest;
import com.algogyeyak.global.s3.dto.PresignedUploadResponse;
import com.algogyeyak.global.s3.dto.PresignedViewResponse;
import com.algogyeyak.global.s3.service.S3PresignService;
import com.algogyeyak.global.s3.util.S3ImagePurpose;
import com.algogyeyak.global.s3.util.S3KeyGenerator;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// AWS S3 연동을 Postman으로 수동 확인하기 위한 임시 컨트롤러 - 실제 도메인 컨트롤러
// (프로필/매물/계약서 이미지 업로드)가 각각 생기면 이 컨트롤러는 지운다.
@RestController
@RequestMapping("/internal/s3-test")
public class S3TestController {

    private final S3PresignService s3PresignService;

    public S3TestController(S3PresignService s3PresignService) {
        this.s3PresignService = s3PresignService;
    }

    @PostMapping("/presigned-upload")
    public ResponseEntity<ApiResponse<PresignedUploadResponse>> presignUpload(
            @RequestParam S3ImagePurpose purpose,
            @RequestParam Long ownerId,
            @Valid @RequestBody PresignedUploadRequest request
    ) {
        String key = switch (purpose) {
            case PROFILE -> S3KeyGenerator.profileImageKey(ownerId, request.getFileExtension());
            case PROPERTY -> S3KeyGenerator.propertyImageKey(ownerId, request.getFileExtension());
            case CONTRACT -> S3KeyGenerator.contractImageKey(ownerId, request.getFileExtension());
        };
        String uploadUrl = s3PresignService.generateUploadUrl(
                key, request.getContentType(), request.getFileSize(), purpose);

        return ResponseEntity.ok(ApiResponse.success(new PresignedUploadResponse(uploadUrl, key)));
    }

    @PostMapping("/confirm")
    public ResponseEntity<ApiResponse<Void>> confirm(
            @RequestParam String key,
            @RequestParam S3ImagePurpose purpose
    ) {
        s3PresignService.confirmUpload(key, purpose);
        return ResponseEntity.ok(ApiResponse.successWithoutData());
    }

    @GetMapping("/presigned-view")
    public ResponseEntity<ApiResponse<PresignedViewResponse>> presignView(
            @RequestParam String key,
            @RequestParam S3ImagePurpose purpose
    ) {
        return ResponseEntity.ok(ApiResponse.success(new PresignedViewResponse(s3PresignService.generateDownloadUrl(key, purpose))));
    }
}