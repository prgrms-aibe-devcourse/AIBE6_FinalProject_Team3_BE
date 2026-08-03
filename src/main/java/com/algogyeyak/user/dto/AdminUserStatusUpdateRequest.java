package com.algogyeyak.user.dto;

import com.algogyeyak.user.enums.UserStatus;
import jakarta.validation.constraints.NotNull;

// status는 ACTIVE/SUSPENDED만 허용한다 - WITHDRAWN은 본인 탈퇴 플로우 전용이라 관리자가 이 API로
// 대신 만들 수 없다(AdminUserService에서 검증).
public record AdminUserStatusUpdateRequest(@NotNull UserStatus status) {
}
