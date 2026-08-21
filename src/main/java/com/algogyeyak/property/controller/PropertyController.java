package com.algogyeyak.property.controller;

import com.algogyeyak.auth.jwt.JwtUserPrincipal;
import com.algogyeyak.global.response.ApiResponse;
import com.algogyeyak.global.response.PageResponse;
import com.algogyeyak.property.dto.PropertyDetailResponse;
import com.algogyeyak.property.dto.PropertyListResponse;
import com.algogyeyak.property.dto.PropertyRegisterRequest;
import com.algogyeyak.property.dto.PropertyRegisterResponse;
import com.algogyeyak.property.dto.PropertySearchCondition;
import com.algogyeyak.property.dto.PropertyUpdateRequest;
import com.algogyeyak.property.entity.PropertyType;
import com.algogyeyak.property.entity.TransactionType;
import com.algogyeyak.property.service.PropertyService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
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
import org.springframework.web.bind.annotation.RequestParam;
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
     * 기본 정렬은 등록일 최신순, 기본 페이지 크기는 20 (최대 100 - PageableUtils.validateMaxSize).
     * region/minArea/maxArea/transactionType/propertyType/minDeposit/maxDeposit/minMonthlyRent/
     * maxMonthlyRent/hasSignal은 전부 선택 파라미터 - 아무것도 안 넘기면 기존과 동일하게 본인 소유
     * 전체 목록을 반환한다. region은 메인 검색창 하나로 받는 자유 텍스트 검색어로, 주소(도로명/지번)든
     * 건물명(Property.title)이든 부분일치하면 매칭된다(5차 멘토링 피드백 6-3, OR 조건).
     * minMonthlyRent/maxMonthlyRent는 전세 매물의 monthlyRent가 항상 null이라 사실상 월세 매물에만 적용된다.
     * hasSignal=true면 확인 필요 신호(checkSignalCount > 0)가 있는 매물만 반환한다(#233).
     */
    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<PropertyListResponse>>> list(
            @AuthenticationPrincipal JwtUserPrincipal principal,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable,
            @RequestParam(required = false) String region,
            @RequestParam(required = false) Double minArea,
            @RequestParam(required = false) Double maxArea,
            @RequestParam(required = false) TransactionType transactionType,
            @RequestParam(required = false) PropertyType propertyType,
            @RequestParam(required = false) Long minDeposit,
            @RequestParam(required = false) Long maxDeposit,
            @RequestParam(required = false) Long minMonthlyRent,
            @RequestParam(required = false) Long maxMonthlyRent,
            @RequestParam(required = false) Boolean hasSignal
    ) {
        PropertySearchCondition condition = new PropertySearchCondition(
                region, minArea, maxArea, transactionType, propertyType,
                minDeposit, maxDeposit, minMonthlyRent, maxMonthlyRent, hasSignal
        );
        PageResponse<PropertyListResponse> response =
                propertyService.getMyProperties(principal.userId(), pageable, condition);
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
