package com.algogyeyak.user.dto;

import com.algogyeyak.user.enums.Role;
import com.algogyeyak.user.enums.UserStatus;

/**
 * 관리자 유저 목록 조회(GET /admin/users) 검색/필터 조건. 전부 선택값(null 허용)이며,
 * null인 조건은 필터링하지 않는다.
 */
public record UserSearchCondition(
        String email,
        String nickname,
        Role role,
        UserStatus status
) {
    public static UserSearchCondition empty() {
        return new UserSearchCondition(null, null, null, null);
    }
}
