package com.algogyeyak.user.dto;

import com.algogyeyak.user.enums.UserStatus;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

// status는 ACTIVE/SUSPENDED만 허용한다 - WITHDRAWN은 본인 탈퇴 플로우 전용이라 관리자가 이 API로
// 대신 만들 수 없다(AdminUserService에서 검증). userIds 상한(100)은 관리자 화면 한 페이지(20건)
// 기준 여러 페이지를 넘나들며 선택해도 넉넉한 값으로, 실수로 전체 유저 id를 통째로 보내는 것을 막는다.
// 원소 자체의 @NotNull은 리스트 검증(@NotEmpty/@Size)과 별개다 - 이게 없으면 [null]처럼 null이 섞인
// 배열이 서비스까지 그대로 들어가 findById(null)에서 IllegalArgumentException(500)으로 죽는다.
public record AdminUserBulkStatusUpdateRequest(
        @NotEmpty @Size(max = 100) List<@NotNull Long> userIds,
        @NotNull UserStatus status
) {
}
