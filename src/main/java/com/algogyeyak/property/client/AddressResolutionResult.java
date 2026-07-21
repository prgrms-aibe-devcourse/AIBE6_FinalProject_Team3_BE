package com.algogyeyak.property.client;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class AddressResolutionResult {

    private final boolean resolved;
    private final String roadAddress;   // 도로명 주소 (검색 결과에 따라 null 가능)
    private final String jibunAddress;  // 지번 주소
    private final Double latitude;
    private final Double longitude;

    public static AddressResolutionResult unresolved() {
        return AddressResolutionResult.builder()
                .resolved(false)
                .build();
    }
}
