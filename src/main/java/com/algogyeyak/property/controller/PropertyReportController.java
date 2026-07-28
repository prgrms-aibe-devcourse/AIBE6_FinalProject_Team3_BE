package com.algogyeyak.property.controller;

import com.algogyeyak.auth.jwt.JwtUserPrincipal;
import com.algogyeyak.global.response.ApiResponse;
import com.algogyeyak.property.dto.PropertyReportRequest;
import com.algogyeyak.property.dto.PropertyReportResponse;
import com.algogyeyak.property.service.PropertyReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 매물 신고. 본인이 등록한 매물만 신고할 수 있으며(존재하지 않거나 삭제된 매물은 404,
 * 본인 소유가 아니면 403), 동일 사용자·동일 매물 중복 신고는 409로 거부한다.
 */
@RestController
@RequestMapping("/properties/{propertyId}/reports")
@RequiredArgsConstructor
public class PropertyReportController {

    private final PropertyReportService propertyReportService;

    @PostMapping
    public ResponseEntity<ApiResponse<PropertyReportResponse>> report(
            @AuthenticationPrincipal JwtUserPrincipal principal,
            @PathVariable Long propertyId,
            @RequestBody PropertyReportRequest request
    ) {
        PropertyReportResponse response = propertyReportService.report(principal.userId(), propertyId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response));
    }
}
