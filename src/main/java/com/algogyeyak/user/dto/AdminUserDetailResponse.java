package com.algogyeyak.user.dto;

import com.algogyeyak.user.entity.User;
import com.algogyeyak.user.enums.Role;
import com.algogyeyak.user.enums.UserStatus;
import java.time.LocalDateTime;

public record AdminUserDetailResponse(
        Long id,
        String email,
        String nickname,
        String profileImageUrl,
        Role role,
        UserStatus status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static AdminUserDetailResponse from(User user) {
        return new AdminUserDetailResponse(
                user.getId(), user.getEmail(), user.getNickname(), user.getProfileImageUrl(),
                user.getRole(), user.getStatus(), user.getCreatedAt(), user.getUpdatedAt());
    }

    // AdminUserService.updateRole()가 조건부 UPDATE(UserRepository.updateRoleIfNotWithdrawn)로
    // role을 바꾼 뒤 쓴다 - 이제 엔티티의 role 필드 자체는 건드리지 않으므로(건드리면 dirty-checking이
    // 커밋 시점에 조건 없는 UPDATE를 한 번 더 내보내 방금 막은 레이스가 되살아난다), 응답에 반영할
    // 새 role 값을 파라미터로 직접 받는다. updatedAt도 마찬가지 이유로 파라미터로 받는다 - JPQL
    // bulk UPDATE라 엔티티의 updatedAt(@LastModifiedDate)이 갱신되지 않으므로, 여기서
    // user.getUpdatedAt()을 그대로 쓰면 DB에 실제로 저장된 값(호출부가 UPDATE 쿼리에 함께 넘긴
    // 값)과 다른 stale한 값을 응답에 실어보내게 된다.
    public static AdminUserDetailResponse from(User user, Role role, LocalDateTime updatedAt) {
        return new AdminUserDetailResponse(
                user.getId(), user.getEmail(), user.getNickname(), user.getProfileImageUrl(),
                role, user.getStatus(), user.getCreatedAt(), updatedAt);
    }

    // from(User, Role, LocalDateTime)과 동일한 이유로, updateStatus()가 조건부 UPDATE로 status를
    // 바꾼 뒤 쓴다.
    public static AdminUserDetailResponse from(User user, UserStatus status, LocalDateTime updatedAt) {
        return new AdminUserDetailResponse(
                user.getId(), user.getEmail(), user.getNickname(), user.getProfileImageUrl(),
                user.getRole(), status, user.getCreatedAt(), updatedAt);
    }
}
