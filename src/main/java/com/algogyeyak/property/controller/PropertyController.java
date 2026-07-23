package com.algogyeyak.property.controller;

import com.algogyeyak.global.response.ApiResponse;
import com.algogyeyak.property.dto.PropertyDetailResponse;
import com.algogyeyak.property.dto.PropertyListResponse;
import com.algogyeyak.property.dto.PropertyRegisterRequest;
import com.algogyeyak.property.dto.PropertyRegisterResponse;
import com.algogyeyak.property.service.PropertyService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/properties")
@RequiredArgsConstructor
public class PropertyController {

    private final PropertyService propertyService;

    /**
     * TODO: 인증(JWT) 구현되면 X-User-Id 헤더 대신 SecurityContext/@AuthenticationPrincipal에서
     * userId를 가져오도록 교체할 것. 지금은 User/인증 도메인이 아직 없어 임시로 헤더로 받는다.
     */
    @PostMapping
    public ResponseEntity<ApiResponse<PropertyRegisterResponse>> register(
            @RequestHeader("X-User-Id") Long userId,
            @Valid @RequestBody PropertyRegisterRequest request
    ) {
        PropertyRegisterResponse response = propertyService.register(userId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response));
    }

    /**
     * 본인이 등록한 매물 목록 조회. (개인 분석 도구 성격상 전체 공개 매물 검색이 아니라 본인 소유 매물만 반환)
     */
    @GetMapping
    public ResponseEntity<ApiResponse<List<PropertyListResponse>>> list(
            @RequestHeader("X-User-Id") Long userId
    ) {
        List<PropertyListResponse> response = propertyService.getMyProperties(userId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /**
     * 매물 상세조회. 본인 소유가 아니면 403(PROPERTY_ACCESS_DENIED), 존재하지 않으면 404(PROPERTY_NOT_FOUND).
     */
    @GetMapping("/{propertyId}")
    public ResponseEntity<ApiResponse<PropertyDetailResponse>> getProperty(
            @RequestHeader("X-User-Id") Long userId,
            @PathVariable Long propertyId
    ) {
        PropertyDetailResponse response = propertyService.getProperty(userId, propertyId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}
