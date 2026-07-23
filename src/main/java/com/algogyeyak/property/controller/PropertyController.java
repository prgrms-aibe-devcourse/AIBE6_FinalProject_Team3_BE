package com.algogyeyak.property.controller;

import com.algogyeyak.auth.jwt.JwtUserPrincipal;
import com.algogyeyak.global.response.ApiResponse;
import com.algogyeyak.property.dto.PropertyDetailResponse;
import com.algogyeyak.property.dto.PropertyListResponse;
import com.algogyeyak.property.dto.PropertyRegisterRequest;
import com.algogyeyak.property.dto.PropertyRegisterResponse;
import com.algogyeyak.property.dto.PropertyUpdateRequest;
import com.algogyeyak.property.service.PropertyService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/properties")
@RequiredArgsConstructor
public class PropertyController {

    private final PropertyService propertyService;

    @PostMapping
    public ResponseEntity<ApiResponse<PropertyRegisterResponse>> register(
            @AuthenticationPrincipal JwtUserPrincipal principal,
            @Valid @RequestBody PropertyRegisterRequest request
    ) {
        PropertyRegisterResponse response = propertyService.register(principal.userId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response));
    }

    /**
     * 본인이 등록한 매물 목록 조회. (개인 분석 도구 성격상 전체 공개 매물 검색이 아니라 본인 소유 매물만 반환)
     */
    @GetMapping
    public ResponseEntity<ApiResponse<List<PropertyListResponse>>> list(
            @AuthenticationPrincipal JwtUserPrincipal principal
    ) {
        List<PropertyListResponse> response = propertyService.getMyProperties(principal.userId());
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /**
     * 매물 상세조회. 본인 소유가 아니면 403(PROPERTY_ACCESS_DENIED), 존재하지 않으면 404(PROPERTY_NOT_FOUND).
     */
    @GetMapping("/{propertyId}")
    public ResponseEntity<ApiResponse<PropertyDetailResponse>> getProperty(
            @AuthenticationPrincipal JwtUserPrincipal principal,
            @PathVariable Long propertyId
    ) {
        PropertyDetailResponse response = propertyService.getProperty(principal.userId(), propertyId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /**
     * 매물 수정. 가격/면적/설명만 수정 대상 (주소/매물유형/거래유형은 등록 시 확정값 유지).
     * 본인 소유가 아니면 403, 존재하지 않으면 404.
     */
    @PatchMapping("/{propertyId}")
    public ResponseEntity<ApiResponse<PropertyDetailResponse>> updateProperty(
            @AuthenticationPrincipal JwtUserPrincipal principal,
            @PathVariable Long propertyId,
            @Valid @RequestBody PropertyUpdateRequest request
    ) {
        PropertyDetailResponse response = propertyService.update(principal.userId(), propertyId, request);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /**
     * 매물 삭제(soft delete). 본인 소유가 아니면 403, 존재하지 않으면 404, 이미 삭제됐으면 409.
     */
    @DeleteMapping("/{propertyId}")
    public ResponseEntity<ApiResponse<Void>> deleteProperty(
            @AuthenticationPrincipal JwtUserPrincipal principal,
            @PathVariable Long propertyId
    ) {
        propertyService.delete(principal.userId(), propertyId);
        return ResponseEntity.ok(ApiResponse.successWithoutData());
    }
}
