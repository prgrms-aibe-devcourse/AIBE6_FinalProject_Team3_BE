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
}
