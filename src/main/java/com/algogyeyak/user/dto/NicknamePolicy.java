package com.algogyeyak.user.dto;

// 한글/영문/숫자만 허용하고 특수문자·공백·이모지는 막는다 - 국내 서비스에서 흔히 쓰이는 닉네임
// 형식 정책(2026-08-06 웹 검색으로 보편성 확인)과 같은 방향이다. SignupRequest(auth 도메인)/
// ProfileRegisterRequest/ProfileUpdateRequest가 공유한다.
//
// frontend는 이 값을 하드코딩해서 복사해두는 대신 GET /users/nickname-policy(UserController)로
// 런타임에 내려받아 쓴다 — PasswordPolicy와 같은 이유로, 이 클래스가 유일한 소스이고 여기만 바꾸면
// 양쪽에 자동으로 반영된다.
public final class NicknamePolicy {

    public static final String PATTERN = "^[가-힣a-zA-Z0-9]{2,20}$";
    public static final String MESSAGE = "닉네임은 한글, 영문, 숫자로 2~20자여야 합니다.";

    // HTML5 <input pattern="...">는 브라우저가 값을 이미 ^(?:...)$ 형태로 감싸 매칭하므로, 여기에
    // ^/$가 또 들어가면 불필요하게 이중 앵커링된다. PATTERN이 항상 ^로 시작해 $로 끝난다는 전제로
    // 그 앞뒤 한 글자씩만 잘라낸다 — PATTERN과 별도로 유지보수할 값이 아니라 항상 같은 소스에서 파생됨.
    public static final String HTML_INPUT_PATTERN = PATTERN.substring(1, PATTERN.length() - 1);

    private NicknamePolicy() {
    }
}
