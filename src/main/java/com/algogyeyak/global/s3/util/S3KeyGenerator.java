package com.algogyeyak.global.s3.util;

import com.algogyeyak.global.error.ErrorCode;
import com.algogyeyak.global.exception.BusinessException;
import java.util.Locale;
import java.util.UUID;

public class S3KeyGenerator {

    private S3KeyGenerator() {
        // 유틸리티 클래스, 인스턴스화 방지
    }

    public static String profileImageKey(Long userId, String fileExt) {
        return String.format(
                "profile-images/%d/%s.%s", userId, UUID.randomUUID(), normalizeExtension(fileExt, S3ImagePurpose.PROFILE)
        );
    }

    public static String propertyImageKey(Long propertyId, String fileExt) {
        return String.format(
                "property-images/%d/%s.%s", propertyId, UUID.randomUUID(), normalizeExtension(fileExt, S3ImagePurpose.PROPERTY)
        );
    }

    public static String contractImageKey(Long userId, String fileExt) {
        return String.format(
                "contract-images/%d/%s.%s", userId, UUID.randomUUID(), normalizeExtension(fileExt, S3ImagePurpose.CONTRACT)
        );
    }

    // 클라이언트가 보낸 확장자를 그대로 키 경로에 넣으면 "/", ".." 등을 통한 경로 조작이 가능해지므로,
    // 화이트리스트에 있는 값으로만 정규화한다 - UUID로 파일명을 새로 만들어도 확장자 자체는 검증 대상이다.
    private static String normalizeExtension(String fileExt, S3ImagePurpose purpose) {
        String normalized = fileExt == null ? "" : fileExt.trim().toLowerCase(Locale.ROOT);
        if (!purpose.allowedExtensions().contains(normalized)) {
            throw new BusinessException(ErrorCode.FILE_EXTENSION_NOT_ALLOWED);
        }
        return normalized;
    }
}
