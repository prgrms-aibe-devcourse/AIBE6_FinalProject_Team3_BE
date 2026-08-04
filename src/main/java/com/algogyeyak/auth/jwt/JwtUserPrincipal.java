package com.algogyeyak.auth.jwt;

import com.algogyeyak.user.enums.Role;

public record JwtUserPrincipal(Long userId, String email, Role role) {
}
