package com.algogyeyak.property.exception;

/**
 * 매물 등록/수정 시 주소 식별 실패 케이스.
 * 공통 예외 핸들러(@ExceptionHandler)에서 422로 매핑해서 응답하면 됨.
 */
public class AddressResolutionFailedException extends RuntimeException {

    public AddressResolutionFailedException(String address) {
        super("입력한 주소를 확인할 수 없습니다: " + address);
    }
}
