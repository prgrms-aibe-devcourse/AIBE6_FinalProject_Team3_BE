package com.algogyeyak.auth.jwt;

import com.algogyeyak.user.enums.Role;

// nickname/profileImageUrl은 JWT 클레임이 아니라 JwtAuthenticationFilter.authenticate()가 매 요청
// 다시 조회하는 User 엔티티에서 그대로 옮겨 담는다(2026-08-12 추가) - AuthController.me()가 같은
// 정보를 얻으려고 필터와 별개로 findById를 한 번 더 호출하던 중복을 없애기 위함이다. email/role과
// 마찬가지로 "이 요청 시점의 최신 DB 값"이라는 보장은 그대로 유지된다(필터가 매 요청 재조회하므로).
public record JwtUserPrincipal(Long userId, String email, Role role, String nickname, String profileImageUrl) {
}
