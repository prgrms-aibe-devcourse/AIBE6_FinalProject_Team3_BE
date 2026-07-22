package com.algogyeyak.property.controller;

import com.algogyeyak.global.response.ApiResponse;
import com.algogyeyak.property.dto.PropertyRegisterRequest;
import com.algogyeyak.property.dto.PropertyRegisterResponse;
import com.algogyeyak.property.service.PropertyService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
}
