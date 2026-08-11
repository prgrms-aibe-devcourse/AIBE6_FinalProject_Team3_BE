package com.algogyeyak.checklist.controller;

import com.algogyeyak.auth.jwt.JwtUserPrincipal;
import com.algogyeyak.checklist.dto.AdminChecklistItemTemplateCreateRequest;
import com.algogyeyak.checklist.dto.AdminChecklistItemTemplateResponse;
import com.algogyeyak.checklist.dto.AdminChecklistItemTemplateUpdateRequest;
import com.algogyeyak.checklist.service.AdminChecklistTemplateService;
import com.algogyeyak.global.response.ApiResponse;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 전용 체크리스트 문항 템플릿 관리 API. /admin/** 는 SecurityConfig에서 ROLE_ADMIN으로만
 * 접근 가능하도록 막혀있다.
 */
@Slf4j
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
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<AdminChecklistItemTemplateResponse> create(
            @AuthenticationPrincipal JwtUserPrincipal principal,
            @Valid @RequestBody AdminChecklistItemTemplateCreateRequest request
    ) {
        AdminChecklistItemTemplateResponse result = adminChecklistTemplateService.create(request);
        // 감사 로그: 문항 추가/수정/삭제는 이후 생성되는 모든 유저 체크리스트에 영향을 미치는데도
        // 별도 DB 감사 테이블이 없으므로 최소한 로그로는 누가 바꿨는지 남긴다.
        log.info("관리자 액션: actorId={} action=CREATE_CHECKLIST_TEMPLATE templateId={}", principal.userId(), result.id());
        return ApiResponse.success(result);
    }

    @PatchMapping("/{templateId}")
    public ApiResponse<AdminChecklistItemTemplateResponse> update(
            @AuthenticationPrincipal JwtUserPrincipal principal,
            @PathVariable Long templateId,
            @Valid @RequestBody AdminChecklistItemTemplateUpdateRequest request
    ) {
        AdminChecklistItemTemplateResponse result = adminChecklistTemplateService.update(templateId, request);
        log.info("관리자 액션: actorId={} action=UPDATE_CHECKLIST_TEMPLATE templateId={}", principal.userId(), templateId);
        return ApiResponse.success(result);
    }

    @DeleteMapping("/{templateId}")
    public ApiResponse<Void> delete(@AuthenticationPrincipal JwtUserPrincipal principal, @PathVariable Long templateId) {
        adminChecklistTemplateService.delete(templateId);
        log.info("관리자 액션: actorId={} action=DELETE_CHECKLIST_TEMPLATE templateId={}", principal.userId(), templateId);
        return ApiResponse.successWithoutData();
    }
}
