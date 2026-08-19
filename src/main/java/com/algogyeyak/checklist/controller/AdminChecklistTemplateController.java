package com.algogyeyak.checklist.controller;

import com.algogyeyak.admin.service.AdminActionLog;
import com.algogyeyak.auth.jwt.JwtUserPrincipal;
import com.algogyeyak.checklist.dto.AdminChecklistItemTemplateCreateRequest;
import com.algogyeyak.checklist.dto.AdminChecklistItemTemplateImageCreateRequest;
import com.algogyeyak.checklist.dto.AdminChecklistItemTemplateImageResponse;
import com.algogyeyak.checklist.dto.AdminChecklistItemTemplateResponse;
import com.algogyeyak.checklist.dto.AdminChecklistItemTemplateUpdateRequest;
import com.algogyeyak.checklist.service.AdminChecklistTemplateService;
import com.algogyeyak.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
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
 * 관리자 전용 체크리스트 문항 템플릿 관리 API. /admin/** 는 SecurityConfig의 URL 패턴
 * (hasRole("ADMIN"))으로 우선 차단되고, 아래 @PreAuthorize는 그 매칭이 어떤 이유로든 빗나가는
 * 경우를 위한 이중 방어다.
 */
@RestController
@RequestMapping("/admin/checklist-templates")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin - Checklist Template", description = "관리자 전용 체크리스트 문항 템플릿 관리 API")
public class AdminChecklistTemplateController {

    private final AdminChecklistTemplateService adminChecklistTemplateService;

    @Operation(summary = "문항 템플릿 목록 조회", description = "표시순서대로 정렬된 전체 문항 템플릿(활성/비활성 포함)을 반환한다.")
    @GetMapping
    public ApiResponse<List<AdminChecklistItemTemplateResponse>> list() {
        return ApiResponse.success(adminChecklistTemplateService.list());
    }

    @Operation(summary = "문항 템플릿 생성", description = "새 문항 템플릿을 생성한다. code를 지정하면 정해진 itemType과의 조합만 허용되고, 같은 code의 활성 문항이 이미 있으면 거부된다.")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<AdminChecklistItemTemplateResponse> create(
            @AuthenticationPrincipal JwtUserPrincipal principal,
            @Valid @RequestBody AdminChecklistItemTemplateCreateRequest request
    ) {
        AdminChecklistItemTemplateResponse result =
                adminChecklistTemplateService.create(principal.userId(), principal.email(), request);
        // 영구 감사 기록은 AdminChecklistTemplateService가 AdminAuditLogger로 남긴다(실제 변경과
        // 같은 트랜잭션) - 이 로그는 실시간 관측(Prometheus/Grafana)용으로 별도 유지한다.
        AdminActionLog.record(principal.userId(), "CREATE_CHECKLIST_TEMPLATE", "templateId", result.id());
        return ApiResponse.success(result);
    }

    @Operation(summary = "문항 템플릿 수정", description = "기존 문항 템플릿을 수정한다. 이미 생성된 유저 체크리스트는 스냅샷 방식이라 영향받지 않고, 앞으로 새로 생성되는 체크리스트에만 반영된다.")
    @PatchMapping("/{templateId}")
    public ApiResponse<AdminChecklistItemTemplateResponse> update(
            @AuthenticationPrincipal JwtUserPrincipal principal,
            @PathVariable Long templateId,
            @Valid @RequestBody AdminChecklistItemTemplateUpdateRequest request
    ) {
        AdminChecklistItemTemplateResponse result =
                adminChecklistTemplateService.update(principal.userId(), principal.email(), templateId, request);
        AdminActionLog.record(principal.userId(), "UPDATE_CHECKLIST_TEMPLATE", "templateId", templateId);
        return ApiResponse.success(result);
    }

    @Operation(summary = "문항 템플릿 삭제", description = "문항 템플릿을 삭제한다. 마지막 남은 문항이거나 마지막 활성 문항이면 거부된다.")
    @DeleteMapping("/{templateId}")
    public ApiResponse<Void> delete(@AuthenticationPrincipal JwtUserPrincipal principal, @PathVariable Long templateId) {
        adminChecklistTemplateService.delete(principal.userId(), principal.email(), templateId);
        AdminActionLog.record(principal.userId(), "DELETE_CHECKLIST_TEMPLATE", "templateId", templateId);
        return ApiResponse.successWithoutData();
    }

    // (2026-08-14) 예시 이미지 관리 - 관리자 업로드 화면 없이 URL만 입력받는다. 실제 파일은 S3 콘솔에
    // 직접 올린다는 전제(관리자 페이지에서 파일 업로드까지 지원하려면 presign/confirm 흐름이 별도로
    // 필요 - AdminChecklistTemplateService.listImages/addImage/deleteImage 주석 참고).
    @Operation(summary = "문항 예시 이미지 목록 조회", description = "문항에 연결된 예시 이미지를 표시순서대로 반환한다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "존재하지 않는 문항 (ADMIN_CHECKLIST_TEMPLATE_NOT_FOUND)")
    @GetMapping("/{templateId}/images")
    public ApiResponse<List<AdminChecklistItemTemplateImageResponse>> listImages(@PathVariable Long templateId) {
        return ApiResponse.success(adminChecklistTemplateService.listImages(templateId));
    }

    @Operation(summary = "문항 예시 이미지 추가", description = "이미 S3에 업로드된 이미지의 URL을 받아 문항에 연결한다(파일 업로드 자체는 지원하지 않음). 새 이미지는 항상 표시순서 맨 뒤에 추가된다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "추가 성공")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "imageUrl이 비어있음")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "존재하지 않는 문항 (ADMIN_CHECKLIST_TEMPLATE_NOT_FOUND)")
    @PostMapping("/{templateId}/images")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<AdminChecklistItemTemplateImageResponse> addImage(
            @AuthenticationPrincipal JwtUserPrincipal principal,
            @PathVariable Long templateId,
            @Valid @RequestBody AdminChecklistItemTemplateImageCreateRequest request
    ) {
        AdminChecklistItemTemplateImageResponse result =
                adminChecklistTemplateService.addImage(principal.userId(), principal.email(), templateId, request);
        AdminActionLog.record(principal.userId(), "ADD_CHECKLIST_TEMPLATE_IMAGE", "templateId", templateId);
        return ApiResponse.success(result);
    }

    @Operation(summary = "문항 예시 이미지 삭제", description = "문항에 연결된 예시 이미지를 삭제한다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "삭제 성공")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "존재하지 않거나 다른 문항 소유의 이미지 (ADMIN_CHECKLIST_TEMPLATE_IMAGE_NOT_FOUND)")
    @DeleteMapping("/{templateId}/images/{imageId}")
    public ApiResponse<Void> deleteImage(
            @AuthenticationPrincipal JwtUserPrincipal principal,
            @PathVariable Long templateId,
            @PathVariable Long imageId
    ) {
        adminChecklistTemplateService.deleteImage(principal.userId(), principal.email(), templateId, imageId);
        AdminActionLog.record(principal.userId(), "DELETE_CHECKLIST_TEMPLATE_IMAGE", "imageId", imageId);
        return ApiResponse.successWithoutData();
    }
}
