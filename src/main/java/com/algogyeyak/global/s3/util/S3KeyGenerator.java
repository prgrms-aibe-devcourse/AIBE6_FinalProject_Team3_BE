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
                S3ImagePurpose.PROFILE.prefix() + "%d/%s.%s",
                userId, UUID.randomUUID(), normalizeExtension(fileExt, S3ImagePurpose.PROFILE)
        );
    }

    // confirmUpload를 호출하기 전에 클라이언트가 보낸 key가 실제로 이 사용자 소유의 프로필 이미지
    // key인지 확인한다 - 이 검증이 없으면 다른 사용자의 key(혹은 property-images/, contract-images/
    // 같은 다른 도메인 key)를 그대로 넘겨서 자기 프로필 사진으로 확정시키는 것을 막을 수 없다.
    public static boolean isProfileImageOwnedBy(Long userId, String key) {
        return key != null && key.startsWith(S3ImagePurpose.PROFILE.prefix() + userId + "/");
    }

    // 매물 이미지는 propertyId가 아니라 userId로 네임스페이스가 나뉜다 - 신규 등록 시점엔 아직
    // propertyId 자체가 없어서(PropertyImageUploadController 클래스 javadoc 참고) 로그인한 사용자
    // 기준으로 키를 만들고, 매물 수정(이미지 교체) 시에도 동일하게 userId를 쓴다. 파라미터명을
    // userId로 맞춰 이 사실을 코드에서도 바로 드러낸다.
    public static String propertyImageKey(Long userId, String fileExt) {
        return String.format(
                S3ImagePurpose.PROPERTY.prefix() + "%d/%s.%s",
                userId, UUID.randomUUID(), normalizeExtension(fileExt, S3ImagePurpose.PROPERTY)
        );
    }

    // isProfileImageOwnedBy와 동일한 패턴 - 아직 어떤 컨트롤러도 이 검증을 호출하지 않는다
    // (PropertyImageUploadController.confirm()에 소유권 검증 자체가 없음, backend property-design.md
    // 전수조사 결과 참고). property 도메인 담당자가 그 컨트롤러에 @AuthenticationPrincipal을 받아
    // 이 메서드로 호출자 소유 여부를 확인하도록 연결해야 실제로 막힌다 - 이 메서드는 그 연결을 위해
    // 미리 준비해둔 공통 유틸이다.
    //
    // (2026-08-12 정정) 파라미터는 propertyId가 아니라 userId다 - propertyImageKey()가 위에서
    // 설명한 대로 userId로 키를 생성하므로, 검증도 같은 기준으로 해야 한다. propertyId로 착각해서
    // 호출하면 정상 소유자의 confirm까지 막히거나(다른 값이라 항상 false) 검증이 무의미해진다.
    public static boolean isPropertyImageOwnedBy(Long userId, String key) {
        return key != null && key.startsWith(S3ImagePurpose.PROPERTY.prefix() + userId + "/");
    }

    public static String contractImageKey(Long userId, String fileExt) {
        return String.format(
                S3ImagePurpose.CONTRACT.prefix() + "%d/%s.%s",
                userId, UUID.randomUUID(), normalizeExtension(fileExt, S3ImagePurpose.CONTRACT)
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
