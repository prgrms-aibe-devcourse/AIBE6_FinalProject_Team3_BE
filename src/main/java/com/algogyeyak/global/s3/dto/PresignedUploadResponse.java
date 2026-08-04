package com.algogyeyak.global.s3.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public class PresignedUploadResponse {
    private final String uploadUrl;
    private final String key;
    // 클라이언트가 실제 PUT 요청에 그대로 실어 보내야 하는 x-amz-tagging 헤더 값(예: "status=pending").
    // presign 시 이 태그를 서명에 포함시켜뒀으므로, 값이 조금이라도 다르면 S3가 서명 불일치(403)로
    // 거부한다 - 프론트가 이 값을 하드코딩해 따로 들고 있지 않고 매번 이 응답을 그대로 쓰게 하기 위함
    // (비밀번호 정책을 프론트가 하드코딩하지 않고 GET /auth/password-policy로 받아쓰는 것과 같은 이유).
    private final String tagging;
}
