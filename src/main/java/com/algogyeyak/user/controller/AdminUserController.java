package com.algogyeyak.user.controller;

import com.algogyeyak.admin.dto.AdminBulkActionResponse;
import com.algogyeyak.admin.service.AdminActionLog;
import com.algogyeyak.auth.jwt.JwtUserPrincipal;
import com.algogyeyak.global.response.ApiResponse;
import com.algogyeyak.global.response.PageResponse;
import com.algogyeyak.user.dto.AdminUserBulkStatusUpdateRequest;
import com.algogyeyak.user.dto.AdminUserDetailResponse;
import com.algogyeyak.user.dto.AdminUserListItemResponse;
import com.algogyeyak.user.dto.AdminUserRoleUpdateRequest;
import com.algogyeyak.user.dto.AdminUserStatusUpdateRequest;
import com.algogyeyak.user.dto.UserSearchCondition;
import com.algogyeyak.user.enums.Role;
import com.algogyeyak.user.enums.UserStatus;
import com.algogyeyak.user.service.AdminUserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 전용 유저 관리 API. /admin/** 는 SecurityConfig의 URL 패턴(hasRole("ADMIN"))으로 우선
 * 차단되고, 아래 @PreAuthorize는 그 매칭이 어떤 이유로든 빗나가는 경우를 위한 이중 방어다.
 */
@RestController
@RequestMapping("/admin/users")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin - User", description = "관리자 전용 유저 관리 API")
public class AdminUserController {

    private final AdminUserService adminUserService;

    @Operation(summary = "유저 목록 조회", description = "이메일/닉네임/권한/상태로 검색·페이지네이션이 가능한 유저 목록을 반환한다.")
    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<AdminUserListItemResponse>>> list(
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable,
            @RequestParam(required = false) String email,
            @RequestParam(required = false) String nickname,
            @RequestParam(required = false) Role role,
            @RequestParam(required = false) UserStatus status
    ) {
        UserSearchCondition condition = new UserSearchCondition(email, nickname, role, status);
        return ResponseEntity.ok(ApiResponse.success(adminUserService.list(pageable, condition)));
    }

    @Operation(summary = "유저 권한 변경", description = "대상 유저의 Role(USER/ADMIN)을 변경한다. 자기 자신은 대상으로 지정할 수 없다.")
    @PatchMapping("/{userId}/role")
    public ResponseEntity<ApiResponse<AdminUserDetailResponse>> updateRole(
            @AuthenticationPrincipal JwtUserPrincipal principal,
            @PathVariable Long userId,
            @Valid @RequestBody AdminUserRoleUpdateRequest request
    ) {
        AdminUserDetailResponse result = adminUserService.updateRole(principal.userId(), principal.email(), userId, request.role());
        // 권한 변경(특히 USER→ADMIN 승격)은 누가 언제 누구에게 했는지 추적 가능해야 한다 -
        // AdminUserService가 AdminAuditLogger로 영구 기록을 남기고, 이 로그는 실시간 관측용으로 별도 유지한다.
        AdminActionLog.record(principal.userId(), "UPDATE_ROLE", "targetUserId", userId, "newRole", request.role());
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @Operation(summary = "유저 상태 변경", description = "대상 유저의 상태(정지/활성 등)를 변경한다. 자기 자신은 대상으로 지정할 수 없다.")
    @PatchMapping("/{userId}/status")
    public ResponseEntity<ApiResponse<AdminUserDetailResponse>> updateStatus(
            @AuthenticationPrincipal JwtUserPrincipal principal,
            @PathVariable Long userId,
            @Valid @RequestBody AdminUserStatusUpdateRequest request
    ) {
        AdminUserDetailResponse result = adminUserService.updateStatus(principal.userId(), principal.email(), userId, request.status());
        AdminActionLog.record(principal.userId(), "UPDATE_STATUS", "targetUserId", userId, "newStatus", request.status());
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @Operation(summary = "유저 상태 일괄 변경", description = "여러 유저의 상태를 한 번에 변경한다. 항목별로 성공/실패가 갈릴 수 있으며(마지막 활성 관리자 보호 등), 응답에 성공/실패 id가 나뉘어 담긴다.")
    @PatchMapping("/bulk-status")
    public ResponseEntity<ApiResponse<AdminBulkActionResponse>> bulkUpdateStatus(
            @AuthenticationPrincipal JwtUserPrincipal principal,
            @Valid @RequestBody AdminUserBulkStatusUpdateRequest request
    ) {
        AdminBulkActionResponse result =
                adminUserService.bulkUpdateStatus(principal.userId(), principal.email(), request.userIds(), request.status());
        // 일괄 처리는 항목별로 성공/실패가 갈릴 수 있어(AdminBulkActionResponse javadoc 참고),
        // 요청받은 id 전체가 아니라 실제로 성공한 id만 기록한다 - 안 그러면 배치가 전부 실패해도
        // (예: 이미 같은 상태이거나 마지막 관리자인 대상만 골라 보낸 경우) 이 관측용 로그에는
        // "처리함"으로 남아 실제 변경이 없었는데도 있었던 것처럼 보인다.
        if (!result.succeededIds().isEmpty()) {
            AdminActionLog.record(principal.userId(), "BULK_UPDATE_STATUS", "targetUserIds", result.succeededIds(),
                    "newStatus", request.status());
        }
        return ResponseEntity.ok(ApiResponse.success(result));
    }
}
