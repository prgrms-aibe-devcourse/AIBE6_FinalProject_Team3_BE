package com.algogyeyak.user.controller;

import com.algogyeyak.auth.jwt.JwtUserPrincipal;
import com.algogyeyak.global.error.ErrorCode;
import com.algogyeyak.global.exception.BusinessException;
import com.algogyeyak.global.response.ApiResponse;
import com.algogyeyak.global.response.PageResponse;
import com.algogyeyak.user.dto.AdminUserDetailResponse;
import com.algogyeyak.user.dto.AdminUserListItemResponse;
import com.algogyeyak.user.dto.AdminUserRoleUpdateRequest;
import com.algogyeyak.user.dto.AdminUserStatusUpdateRequest;
import com.algogyeyak.user.dto.UserSearchCondition;
import com.algogyeyak.user.enums.Role;
import com.algogyeyak.user.enums.UserStatus;
import com.algogyeyak.user.service.AdminUserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 전용 유저 관리 API. /admin/** 는 SecurityConfig에서 ROLE_ADMIN으로만 접근 가능하도록 막혀있다.
 */
@Slf4j
@RestController
@RequestMapping("/admin/users")
@RequiredArgsConstructor
public class AdminUserController {

    private final AdminUserService adminUserService;

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

    @GetMapping("/{userId}")
    public ResponseEntity<ApiResponse<AdminUserDetailResponse>> detail(@PathVariable Long userId) {
        return ResponseEntity.ok(ApiResponse.success(adminUserService.getDetail(userId)));
    }

    @PatchMapping("/{userId}/role")
    public ResponseEntity<ApiResponse<AdminUserDetailResponse>> updateRole(
            @AuthenticationPrincipal JwtUserPrincipal principal,
            @PathVariable Long userId,
            @Valid @RequestBody AdminUserRoleUpdateRequest request
    ) {
        rejectSelf(principal, userId);
        AdminUserDetailResponse result = adminUserService.updateRole(userId, request.role());
        // 감사 로그: 권한 변경(특히 USER→ADMIN 승격)은 누가 언제 누구에게 했는지 추적 가능해야 한다 -
        // 별도 DB 감사 테이블은 없으므로 최소한 로그로는 남긴다.
        log.info("관리자 액션: actorId={} action=UPDATE_ROLE targetUserId={} newRole={}",
                principal.userId(), userId, request.role());
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @PatchMapping("/{userId}/status")
    public ResponseEntity<ApiResponse<AdminUserDetailResponse>> updateStatus(
            @AuthenticationPrincipal JwtUserPrincipal principal,
            @PathVariable Long userId,
            @Valid @RequestBody AdminUserStatusUpdateRequest request
    ) {
        rejectSelf(principal, userId);
        AdminUserDetailResponse result = adminUserService.updateStatus(userId, request.status());
        log.info("관리자 액션: actorId={} action=UPDATE_STATUS targetUserId={} newStatus={}",
                principal.userId(), userId, request.status());
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    // 관리자가 실수로 자기 자신의 권한을 강등하거나 자기 자신을 정지시켜 스스로를 잠그는 사고를 막는다.
    private void rejectSelf(JwtUserPrincipal principal, Long targetUserId) {
        if (principal.userId().equals(targetUserId)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "자기 자신의 권한/상태는 변경할 수 없습니다.");
        }
    }
}
