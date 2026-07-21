package com.algogyeyak.auth.oauth;

/**
 * 쿠키 값이 손상되었거나(파싱 실패) 서명 검증에 실패했을 때 던진다.
 * 클라이언트가 보낸 값 자체의 문제이므로, 호출부는 이를 잡아 "저장된 값 없음"으로 취급해야 한다.
 */
public class CookieTamperedException extends RuntimeException {

    public CookieTamperedException(String message) {
        super(message);
    }

    public CookieTamperedException(String message, Throwable cause) {
        super(message, cause);
    }
}
