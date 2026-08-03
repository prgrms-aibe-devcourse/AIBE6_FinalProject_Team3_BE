package com.algogyeyak.global.s3.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public class PresignedViewResponse {
    private final String viewUrl;
}
