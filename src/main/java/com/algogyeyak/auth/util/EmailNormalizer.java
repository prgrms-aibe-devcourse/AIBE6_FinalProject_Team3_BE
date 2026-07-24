package com.algogyeyak.auth.util;

import java.util.Locale;

// 로컬 가입/로그인과 OAuth 연동이 이메일을 서로 다른 대소문자·공백 기준으로 저장/조회하면 같은
// 사람의 계정인데도 다른 계정으로 취급되어 자동 연동이 실패하거나(대소문자만 다른 이메일), DB
// collation에 따라 유니크 제약 결과가 달라질 수 있다. 이메일을 저장하거나 조회하는 모든 지점은
// 반드시 이 메서드를 거쳐야 한다.
public final class EmailNormalizer {

    private EmailNormalizer() {
    }

    public static String normalize(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }
}
