package com.algogyeyak.user.dto;

import com.algogyeyak.user.enums.Role;
import jakarta.validation.constraints.NotNull;

public record AdminUserRoleUpdateRequest(@NotNull Role role) {
}
