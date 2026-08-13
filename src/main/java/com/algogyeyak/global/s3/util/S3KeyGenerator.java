package com.algogyeyak.global.s3.util;

import com.algogyeyak.global.error.ErrorCode;
import com.algogyeyak.global.exception.BusinessException;
import java.util.Locale;
import java.util.UUID;

public class S3KeyGenerator {

    private static final String PROFILE_IMAGE_PREFIX = "profile-images/";
    private static final String PROPERTY_IMAGE_PREFIX = "property-images/";

    private S3KeyGenerator() {
        // 유틸리티 클래스, 인스턴스화 방지
    }

    public static String profileImageKey(Long userId, String fileExt) {
        return String.format(
                PROFILE_IMAGE_PREFIX + "%d/%s.%s", userId, UUID.randomUUID(), normalizeExtension(fileExt, S3ImagePurpose.PROFILE)
        );
    }

    // confirmUpload를 호출하기 전에 클라이언트가 보낸 key가 실제로 이 사용자 소유의 프로필 이미지
    // key인지 확인한다 - 이 검증이 없으면 다른 사용자의 key(혹은 property-images/, contract-images/
    // 같은 다른 도메인 key)를 그대로 넘겨서 자기 프로필 사진으로 확정시키는 것을 막을 수 없다.
    public static boolean isProfileImageOwnedBy(Long userId, String key) {
        return key != null && key.startsWith(PROFILE_IMAGE_PREFIX + userId + "/");
    }

    public static String propertyImageKey(Long propertyId, String fileExt) {
        return String.format(
                PROPERTY_IMAGE_PREFIX + "%d/%s.%s", propertyId, UUID.randomUUID(), normalizeExtension(fileExt, S3ImagePurpose.PROPERTY)
        );
    }

    // confirm을 호출하기 전에 클라이언트가 보낸 key가 실제로 이 사용자 명의로 발급된 매물 이미지
    // key인지 확인한다 - isProfileImageOwnedBy와 동일한 목적: 다른 사용자의 key나 다른 도메인의
    // key(profile-images/, contract-images/)를 그대로 넘겨서 확정시키는 것을 막는다.
    // 주의: 매물 이미지 key는 propertyId가 아니라 업로드를 요청한 userId 기준으로 발급된다
    // (issueUploadUrl()이 propertyImageKey(principal.userId(), ...)를 호출) - 매개변수명은
    // propertyImageKey()와의 호출 형태를 맞추기 위해 유지하되 실제로는 userId가 들어온다.
    public static boolean isPropertyImageOwnedBy(Long userId, String key) {
        return key != null && key.startsWith(PROPERTY_IMAGE_PREFIX + userId + "/");
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
