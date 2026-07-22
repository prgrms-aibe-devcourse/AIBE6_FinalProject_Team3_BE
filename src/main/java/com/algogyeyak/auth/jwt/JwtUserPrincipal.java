package com.algogyeyak.auth.jwt;

import com.algogyeyak.user.entity.Role;

public record JwtUserPrincipal(Long userId, String email, String nickname, Role role) {
}
