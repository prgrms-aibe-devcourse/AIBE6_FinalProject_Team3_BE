package com.algogyeyak.global.s3.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public class PresignedUploadResponse {
    private final String uploadUrl;
    private final String key;
}
