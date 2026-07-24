package com.algogyeyak.auth.dto;

// BCrypt는 72바이트를 넘는 부분을 조용히 잘라버린다. 문자 수 기준 {8,72}만으로는 이 한계를 정확히
// 보장할 수 없어(멀티바이트 문자는 1자가 여러 바이트), ASCII 출력 가능 문자(0x21~0x7E, 공백 제외)로
// 제한해 문자 수와 바이트 수를 일치시킨다. SignupRequest/PasswordUpdateRequest가 공유한다.
final class PasswordPolicy {

    static final String PATTERN = "^(?=.*[A-Za-z])(?=.*\\d)[\\x21-\\x7E]{8,72}$";
    static final String MESSAGE = "비밀번호는 영문과 숫자를 포함한 8~72자의 영문/숫자/기호(공백 제외)여야 합니다.";

    private PasswordPolicy() {
    }
}
