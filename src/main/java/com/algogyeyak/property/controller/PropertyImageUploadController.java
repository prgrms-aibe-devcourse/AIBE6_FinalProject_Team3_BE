package com.algogyeyak.property.controller;

import com.algogyeyak.auth.jwt.JwtUserPrincipal;
import com.algogyeyak.global.response.ApiResponse;
import com.algogyeyak.global.s3.service.S3PresignService;
import com.algogyeyak.global.s3.util.S3ImagePurpose;
import com.algogyeyak.global.s3.util.S3KeyGenerator;
import com.algogyeyak.property.dto.PropertyImageConfirmRequest;
import com.algogyeyak.property.dto.PropertyImageConfirmResponse;
import com.algogyeyak.property.dto.PropertyImageUploadUrlRequest;
import com.algogyeyak.property.dto.PropertyImageUploadUrlResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 매물 이미지 업로드 API. 클라이언트는 1) upload-url로 presigned URL을 받아 2) S3에 직접 PUT한 뒤
 * 3) confirm으로 업로드 완료를 확인하고 영구 조회 URL을 받는다. 이 URL을 매물 등록/수정 요청의
 * images[].imageUrl로 그대로 넣어 제출하면 된다.
 *
 * 신규 등록 시점엔 아직 propertyId가 없어서 S3 키 네임스페이스는 propertyId가 아니라 로그인한
 * userId 기준으로 생성한다 - 매물 수정(이미지 교체) 시에도 동일하게 userId를 쓴다.
 *
 * 이 컨트롤러가 global.s3.controller.S3TestController(Postman 테스트용 임시 컨트롤러)의
 * PROPERTY 용도를 대체한다.
 */
@RestController
@RequestMapping("/properties/images")
@RequiredArgsConstructor
public class PropertyImageUploadController {

    private final S3PresignService s3PresignService;

    @PostMapping("/upload-url")
    public ResponseEntity<ApiResponse<PropertyImageUploadUrlResponse>> issueUploadUrl(
            @AuthenticationPrincipal JwtUserPrincipal principal,
            @Valid @RequestBody PropertyImageUploadUrlRequest request
    ) {
        String key = S3KeyGenerator.propertyImageKey(principal.userId(), request.fileExtension());
        String uploadUrl = s3PresignService.generateUploadUrl(
                key, request.contentType(), request.fileSize(), S3ImagePurpose.PROPERTY
        );

        return ResponseEntity.ok(ApiResponse.success(new PropertyImageUploadUrlResponse(uploadUrl, key)));
    }

    @PostMapping("/confirm")
    public ResponseEntity<ApiResponse<PropertyImageConfirmResponse>> confirm(
            @Valid @RequestBody PropertyImageConfirmRequest request
    ) {
        s3PresignService.confirmUpload(request.key(), S3ImagePurpose.PROPERTY);
        String imageUrl = s3PresignService.generateDownloadUrl(request.key(), S3ImagePurpose.PROPERTY);

        return ResponseEntity.ok(ApiResponse.success(new PropertyImageConfirmResponse(imageUrl)));
    }
}
