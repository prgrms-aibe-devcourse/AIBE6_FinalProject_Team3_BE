package com.algogyeyak.auth.dto;

// BCrypt는 72바이트를 넘는 부분을 조용히 잘라버린다. 문자 수 기준 {8,72}만으로는 이 한계를 정확히
// 보장할 수 없어(멀티바이트 문자는 1자가 여러 바이트), ASCII 출력 가능 문자(0x21~0x7E, 공백 제외)로
// 제한해 문자 수와 바이트 수를 일치시킨다. SignupRequest/PasswordUpdateRequest가 공유한다.
//
// frontend는 이 값을 하드코딩해서 복사해두는 대신 GET /auth/password-policy(AuthController)로
// 런타임에 내려받아 쓴다 — 이 클래스가 유일한 소스이고, 여기만 바꾸면 양쪽에 자동으로 반영된다.
public final class PasswordPolicy {

    public static final String PATTERN = "^(?=.*[A-Za-z])(?=.*\\d)[\\x21-\\x7E]{8,72}$";
    public static final String MESSAGE = "비밀번호는 영문과 숫자를 포함한 8~72자의 영문/숫자/기호(공백 제외)여야 합니다.";

    // HTML5 <input pattern="...">는 브라우저가 값을 이미 ^(?:...)$ 형태로 감싸 매칭하므로, 여기에
    // ^/$가 또 들어가면 불필요하게 이중 앵커링된다. PATTERN이 항상 ^로 시작해 $로 끝난다는 전제로
    // 그 앞뒤 한 글자씩만 잘라낸다 — PATTERN과 별도로 유지보수할 값이 아니라 항상 같은 소스에서 파생됨.
    public static final String HTML_INPUT_PATTERN = PATTERN.substring(1, PATTERN.length() - 1);

    private PasswordPolicy() {
    }
}
