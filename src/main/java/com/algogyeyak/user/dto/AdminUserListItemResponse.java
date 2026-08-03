package com.algogyeyak.user.dto;

import com.algogyeyak.user.entity.User;
import com.algogyeyak.user.enums.Role;
import com.algogyeyak.user.enums.UserStatus;
import java.time.LocalDateTime;

public record AdminUserListItemResponse(
        Long id,
        String email,
        String nickname,
        Role role,
        UserStatus status,
        LocalDateTime createdAt
) {
    public static AdminUserListItemResponse from(User user) {
        return new AdminUserListItemResponse(
                user.getId(), user.getEmail(), user.getNickname(), user.getRole(), user.getStatus(), user.getCreatedAt());
    }
}
