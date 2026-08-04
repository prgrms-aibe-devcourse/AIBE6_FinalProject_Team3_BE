package com.algogyeyak.checklist.controller;

import com.algogyeyak.checklist.dto.AdminChecklistItemTemplateCreateRequest;
import com.algogyeyak.checklist.dto.AdminChecklistItemTemplateResponse;
import com.algogyeyak.checklist.dto.AdminChecklistItemTemplateUpdateRequest;
import com.algogyeyak.checklist.service.AdminChecklistTemplateService;
import com.algogyeyak.global.response.ApiResponse;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 전용 체크리스트 문항 템플릿 관리 API. /admin/** 는 SecurityConfig에서 ROLE_ADMIN으로만
 * 접근 가능하도록 막혀있다.
 */
@RestController
@RequestMapping("/admin/checklist-templates")
@RequiredArgsConstructor
public class AdminChecklistTemplateController {

    private final AdminChecklistTemplateService adminChecklistTemplateService;

    @GetMapping
    public ApiResponse<List<AdminChecklistItemTemplateResponse>> list() {
        return ApiResponse.success(adminChecklistTemplateService.list());
    }

    @PostMapping
    public ApiResponse<AdminChecklistItemTemplateResponse> create(
            @Valid @RequestBody AdminChecklistItemTemplateCreateRequest request
    ) {
        return ApiResponse.success(adminChecklistTemplateService.create(request));
    }

    @PatchMapping("/{templateId}")
    public ApiResponse<AdminChecklistItemTemplateResponse> update(
            @PathVariable Long templateId,
            @Valid @RequestBody AdminChecklistItemTemplateUpdateRequest request
    ) {
        return ApiResponse.success(adminChecklistTemplateService.update(templateId, request));
    }

    @DeleteMapping("/{templateId}")
    public ApiResponse<Void> delete(@PathVariable Long templateId) {
        adminChecklistTemplateService.delete(templateId);
        return ApiResponse.successWithoutData();
    }
}
